package konstructs.protocol

import scala.collection.JavaConverters._
import akka.actor.{Actor, Props, ActorRef, Stash, PoisonPill}
import akka.io.Tcp
import akka.io.TcpPipelineHandler.{Init, WithinActorContext}
import akka.util.ByteString
import konstructs.{PlayerActor, UniverseActor, DbActor}
import konstructs.api._
import konstructs.api.messages.Said
import konstructs.shard.ChunkPosition

class ClientActor(init: Init[WithinActorContext, ByteString, ByteString],
                  universe: ActorRef,
                  factory: BlockFactory,
                  textures: Array[Byte])
    extends Actor
    with Stash {
  import DbActor.BlockList
  import UniverseActor.CreatePlayer
  import ClientActor._
  import PlayerActor._

  implicit val bo = java.nio.ByteOrder.BIG_ENDIAN

  private var player: PlayerInfo = null

  private def readData[T](conv: String => T, data: String): List[T] = {
    val comma = data.indexOf(',')
    if (comma > 0) {
      val i = conv(data.take(comma))
      i :: readData(conv, data.drop(comma + 1))
    } else {
      val i = conv(data)
      i :: Nil
    }
  }

  def handle(data: ByteString) = {
    val command = data.decodeString("ascii")
    if (command.startsWith("P,")) {
      val floats = readData(_.toFloat, command.drop(2))
      player.actor ! Position(floats(0), floats(1), floats(2), floats(3), floats(4))
    } else if (command.startsWith("C,")) {
      val ints = readData(_.toInt, command.drop(2))
      player.actor ! DbActor.SendBlocks(ChunkPosition(ints(0), ints(1), ints(2)))
    } else if (command.startsWith("M,")) {
      val ints = readData(_.toInt, command.drop(2))
      if (ints(0) != 0) {
        player.actor ! Action(new konstructs.api.Position(ints(1), ints(2), ints(3)),
                              Orientation.get(Direction.get(ints(6)), Rotation.get(ints(7))),
                              ints(4),
                              ints(5))
      } else {
        player.actor ! Action(null, null, ints(4), ints(5))
      }
    } else if (command.startsWith("T,")) {
      val message = command.substring(2)
      player.actor ! Say(message)
    } else if (command.startsWith("I")) {
      player.actor ! CloseInventory
    } else if (command.startsWith("R,")) {
      val ints = readData(_.toInt, command.drop(2))
      player.actor ! SelectItem(ints(0), ints(1))
    } else if (command.startsWith("D,")) {
      val ints = readData(_.toInt, command.drop(2))
      player.actor ! SetViewDistance(ints(0))
    }
  }

  def receive = {
    case init.Event(data) =>
      val command = data.decodeString("ascii")
      if (command.startsWith(s"V,$Version,")) {
        val strings = readData(s => s, command.drop(2))
        val auth = Authenticate(strings(0) toInt, strings(1), strings(2))
        println(s"Player ${auth.name} connected with protocol version ${auth.version}")
        universe ! CreatePlayer(auth.name, auth.token)
        context.become(waitForPlayer(sender))
      } else {
        sendError(sender, s"This server only supports protocol version $Version")
        context.stop(self)
      }
    case _: Tcp.ConnectionClosed =>
      context.stop(self)
  }

  def waitForPlayer(pipe: ActorRef): Receive = {
    case p: PlayerInfo =>
      player = p
      send(pipe, s"U,${p.pid},${p.pos.x},${p.pos.y},${p.pos.z},${p.pos.rx},${p.pos.ry}")
      sendPlayerNick(pipe, p.pid, p.nick)
      sendBlockTypes(pipe)
      sendTextures(pipe)
      unstashAll()
      context.become(ready(pipe))
    case init.Event(data) =>
      stash()
  }

  def ready(pipe: ActorRef): Receive = {
    case init.Event(command) =>
      handle(command)
    case BlockList(chunk, data) =>
      sendBlocks(pipe, chunk, data.data)
    case ChunkUpdate(p, q, k) =>
      sendChunkUpdate(pipe, p, q, k)
    case b: SendBlock =>
      sendBlock(pipe, b)
    case BeltUpdate(items) =>
      sendBelt(pipe, items)
    case InventoryUpdate(view) =>
      sendInventory(pipe, view.getItems.asScala.toMap)
    case p: PlayerMovement =>
      sendPlayerMovement(pipe, p)
    case PlayerNick(pid, nick) =>
      sendPlayerNick(pipe, pid, nick)
    case PlayerLogout(pid) =>
      sendPlayerLogout(pipe, pid)
    case s: Said =>
      sendSaid(pipe, s.getText)
    case HeldStack(stack) =>
      if (stack != null)
        sendHeldStack(pipe, stack.size, factory.getW(stack), stack.getHead.getHealth.getHealth)
      else
        sendHeldStack(pipe, 0, -1, 0)
    case Time(t) =>
      sendTime(pipe, t)
    case _: Tcp.ConnectionClosed =>
      context.stop(self)
  }

  override def postStop {
    if (player != null)
      player.actor ! PoisonPill
  }

  def sendError(pipe: ActorRef, error: String) {
    send(pipe, s"E,$error")
  }

  def sendPlayerNick(pipe: ActorRef, pid: Int, nick: String) {
    send(pipe, s"N,$pid,$nick")
  }

  def sendTime(pipe: ActorRef, t: Long) {
    send(pipe, s"T,$t")
  }

  def sendChunkUpdate(pipe: ActorRef, p: Int, q: Int, k: Int) {
    send(pipe, s"c,$p,$q,$k")
  }

  def sendSaid(pipe: ActorRef, msg: String) {
    send(pipe, s"T,$msg")
  }

  def sendPlayerLogout(pipe: ActorRef, pid: Int) {
    send(pipe, s"D,$pid")
  }

  def sendPlayerMovement(pipe: ActorRef, p: PlayerMovement) {
    send(pipe, s"P,${p.pid},${p.pos.x},${p.pos.y},${p.pos.z},${p.pos.rx},${p.pos.ry}")
  }

  def sendBelt(pipe: ActorRef, items: Array[Stack]) {
    for ((stack, i) <- items.zipWithIndex) {
      if (stack != null) {
        send(pipe, s"G,${i},${stack.size},${factory.getW(stack)},${stack.getHead.getHealth.getHealth}")
      } else {
        send(pipe, s"G,${i},0,0,0")
      }
    }
  }

  def sendHeldStack(pipe: ActorRef, size: Int, w: Int, health: Int) {
    send(pipe, s"i,$size,$w,$health")
  }

  def sendInventory(pipe: ActorRef, items: Map[Integer, Stack]) {
    for ((p, stack) <- items) {
      if (stack != null) {
        send(pipe, s"I,${p},${stack.size},${factory.getW(stack)},${stack.getHead.getHealth.getHealth}")
      } else {
        send(pipe, s"I,${p},0,0,0")
      }
    }
  }

  def sendBlock(pipe: ActorRef, b: SendBlock) {
    send(pipe, s"B,${b.p},${b.q},${b.x},${b.y},${b.z},${b.w}")
  }

  def sendBlocks(pipe: ActorRef, chunk: ChunkPosition, blocks: Array[Byte]) {
    val data = ByteString.newBuilder.putByte(C).putInt(chunk.p).putInt(chunk.q).putInt(chunk.k).putBytes(blocks).result
    send(pipe, data)
  }

  def sendTextures(pipe: ActorRef) {
    val data = ByteString.newBuilder.putByte(M).putBytes(textures).result
    send(pipe, data)
  }

  def sendBlockTypes(pipe: ActorRef) {
    val types = factory.getBlockTypes().asScala.map {
      case (id, t) => factory.getW(id) -> t
    }
    for ((w, t) <- types) {
      sendBlockType(pipe, w, t)
    }
  }

  def booleanToInt(b: Boolean): Int = if (b) 1 else 0

  def sendBlockType(pipe: ActorRef, w: Int, t: BlockType) {
    val isObstacle = booleanToInt(t.isObstacle)
    val isTransparent = booleanToInt(t.isTransparent)
    val isOrientable = booleanToInt(t.isOrientable)
    val faces = t.getFaces
    send(pipe,
         s"W,$w,${t.getBlockShape.getShape},${t.getBlockState.getState},$isObstacle,$isTransparent,${faces(0)},${faces(
           1)},${faces(2)},${faces(3)},${faces(4)},${faces(5)},${isOrientable}")
  }

  def send(pipe: ActorRef, msg: ByteString) {
    pipe ! init.Command(msg)
  }

  def send(pipe: ActorRef, msg: String) {
    send(pipe, ByteString(msg, "ascii"))
  }
}

object ClientActor {
  val C = 'C'.toByte
  val B = 'B'.toByte
  val V = 'V'.toByte
  val P = 'P'.toByte
  val M = 'M'.toByte
  val Version = 9
  case object Setup
  def props(init: Init[WithinActorContext, ByteString, ByteString],
            universe: ActorRef,
            factory: BlockFactory,
            textures: Array[Byte]) =
    Props(classOf[ClientActor], init, universe, factory, textures)
}
