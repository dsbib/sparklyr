package sparklyr

import java.io.{DataInputStream, DataOutputStream}
import java.io.{File, FileOutputStream, IOException}
import java.net.{InetAddress, InetSocketAddress, ServerSocket, Socket}
import java.util.concurrent.TimeUnit

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.{ChannelFuture, ChannelInitializer, EventLoopGroup}
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.LengthFieldBasedFrameDecoder
import io.netty.handler.codec.bytes.{ByteArrayDecoder, ByteArrayEncoder}

import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.sql.hive.HiveContext

import scala.util.Try

/*
 * Backend is the main class for the sparklyr backend.
 *
 * The Backend class is launched from Spark through spark-submit with the following
 * paramters: port, session and service.
 *
 *   port: Defined the port the gateway should listen to.
 *   sessionid: An identifier to track each session and reuse sessions if needed.
 *   service: A flag to keep this service running until the client forces it to
 *            shut down by calling "terminateBackend" through the invoke interface.
 *   remote: A flag to enable the gateway and backend to accept remote connections.
 *
 * On launch, the Backend will open the gateway socket on the port specified by
 * the shell parameter on launch.
 *
 * If the port is already in use, the Backend will attempt to use the existing
 * service running in this port as a sparklyr gateway and register itself. Therefore,
 * the gateway socket serves not only as an interface to connect to the current
 * instance, but also as bridge to other sparklyr backend instances running in this
 * machine. This mechanism is the replacement of the ports file which used to
 * communicate ports information back to the sparklyr client, in this model, one
 * and only one gateway runs and provides the mapping between sessionids and ports.
 *
 * While running, the Backend loops under a while(true) loop and blocks under the
 * gateway socket accept() method waiting for clients to connect. Once a client
 * connects, it launches a thread to process the client requests and blocks again.
 *
 * In the gateway socket, the thread listens for two commands: GetPorts or
 * RegisterInstance.
 *
 * GetPorts provides a mapping to the gateway/backend ports. In a single-client/
 * single-backend scenario, the sessionid from the current instance and the
 * requested instance will match, a backend gets created and the backend port
 * communicated back to the client. In a multiple-backend scenario, GetPort
 * will look at the sessionid mapping table and return a redirect port if needed,
 * this enables the system to run multiple backends all using the same gateway
 * port but still support redirection to the correct sessionid backend. Finally,
 * if the sessionis is not found, a delay is introduced in case an existing
 * backend is launching an about to register.
 *
 * RegiterInstance provides a way to map sessionids to ports to other instances
 * of sparklyr running in this machines. During launch, if the gateway port is
 * already in use, the instance being launched will use this api to communicate
 * to the main gateway the port in which this instance will listen to.
 */
class Backend(loggerParam: Logger) {

  private[this] var channelFuture: ChannelFuture = null
  private[this] var bootstrap: ServerBootstrap = null
  private[this] var bossGroup: EventLoopGroup = null
  private[this] var inetAddress: InetSocketAddress = null

  var logger = loggerParam

  def init(remote: Boolean): Int = {
    if (remote) {
      val anyIpAddress = Array[Byte](0, 0, 0, 0)
      val anyInetAddress = InetAddress.getByAddress(anyIpAddress)

      inetAddress = new InetSocketAddress(anyInetAddress, 0)
    }
    else {
      inetAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0)
    }

    val conf = new SparkConf()
    bossGroup = new NioEventLoopGroup(conf.getInt("sparklyr.backend.threads", 2))
    val workerGroup = bossGroup
    val handler = new BackendHandler(this)

    bootstrap = new ServerBootstrap()
      .group(bossGroup, workerGroup)
      .channel(classOf[NioServerSocketChannel])

    bootstrap.childHandler(new ChannelInitializer[SocketChannel]() {
      def initChannel(ch: SocketChannel): Unit = {
        ch.pipeline()
          .addLast("encoder", new ByteArrayEncoder())
          .addLast("frameDecoder",
            new LengthFieldBasedFrameDecoder(Integer.MAX_VALUE, 0, 4, 0, 4))
          .addLast("decoder", new ByteArrayDecoder())
          .addLast("handler", handler)
      }
    })

    channelFuture = bootstrap.bind(inetAddress)
    channelFuture.syncUninterruptibly()
    channelFuture.channel().localAddress().asInstanceOf[InetSocketAddress].getPort()
  }

  def run(): Unit = {
    channelFuture.channel.closeFuture().syncUninterruptibly()
  }

  def close(): Unit = {
    if (channelFuture != null) {
      // close is a local operation and should finish within milliseconds; timeout just to be safe
      channelFuture.channel().close().awaitUninterruptibly(10, TimeUnit.SECONDS)
      channelFuture = null
    }
    if (bootstrap != null && bootstrap.group() != null) {
      bootstrap.group().shutdownGracefully()
    }
    if (bootstrap != null && bootstrap.childGroup() != null) {
      bootstrap.childGroup().shutdownGracefully()
    }
    bootstrap = null
  }

}

object Backend {
  private[this] var isService: Boolean = false
  private[this] var isRemote: Boolean = false
  private[this] var isWorker: Boolean = false

  private[this] var gatewayServerSocket: ServerSocket = null
  private[this] var port: Int = 0
  private[this] var sessionId: Int = 0

  private[this] var sc: SparkContext = null
  private[this] var hc: HiveContext = null

  private[this] var sessionsMap: Map[Int, Int] = Map()

  private[this] var inetAddress: InetAddress = InetAddress.getLoopbackAddress()

  private[this] var logger: Logger = new Logger("Session", 0);

  object GatewayOperattions extends Enumeration {
    val GetPorts, RegisterInstance = Value
  }

  def getOrCreateHiveContext(sc: SparkContext): HiveContext = {
    if (hc == null) {
      hc = new HiveContext(sc)
    }

    hc
  }

  def getSparkContext(): SparkContext = {
    sc
  }

  def setSparkContext(nsc: SparkContext): Unit = {
    sc = nsc
  }

  def portIsAvailable(port: Int) = {
    var ss: ServerSocket = null
    var available = false

    Try {
        ss = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())
        available = true
    }

    if (ss != null) {
        Try {
            ss.close();
        }
    }

    available
  }

  def main(args: Array[String]): Unit = {
    if (args.length > 4 || args.length < 2) {
      System.err.println(
        "Usage: Backend port id [--service] [--remote]\n" +
        "  port:      port the gateway will listen to\n" +
        "  id:        arbitrary numeric identifier for this backend session\n" +
        "  --service: prevents closing the connection from closing the backen\n" +
        "  --remote:  allows the gateway to accept remote connections\n"
      )

      System.exit(-1)
    }

    val port = args(0).toInt
    val sessionId = args(1).toInt
    val isService = args.contains("--service")
    val isRemote = args.contains("--remote")

    init(port, sessionId, isService, isRemote, false)
  }

  def init(portParam: Int,
           sessionIdParam: Int,
           isServiceParam: Boolean,
           isRemoteParam: Boolean,
           isWorkerParam: Boolean): Unit = {

    port = portParam
    sessionId = sessionIdParam
    isService = isServiceParam
    isRemote = isRemoteParam
    isWorker = isWorkerParam

    logger = new Logger("Session", sessionId)

    logger.log("is starting under " +
        InetAddress.getLocalHost.getHostAddress + "/" +
        InetAddress.getLoopbackAddress().getHostAddress +
        " port " + port)

    if (isRemote) {
      logger.log("is configuring for remote connections")

      val anyIpAddress = Array[Byte](0, 0, 0, 0)
      inetAddress = InetAddress.getByAddress(anyIpAddress)
    }

    try {
      if (portIsAvailable(port))
      {
        logger.log("found port " + port + " is available")
        logger = new Logger("Gateway", sessionId)

        gatewayServerSocket = new ServerSocket(port, 1, inetAddress)
      }
      else
      {
        logger.log("found port " + port + " is not available")
        logger = new Logger("Backend", sessionId)
        if (isWorker) logger = new Logger("Worker", sessionId)

        gatewayServerSocket = new ServerSocket(0, 1, inetAddress)
        val gatewayPort = port
        port = gatewayServerSocket.getLocalPort()

        val success = register(gatewayPort, sessionId, port)
        if (!success) {
          logger.logError("failed to register on gateway port " + gatewayPort)
          System.exit(1)
        }
      }

      gatewayServerSocket.setSoTimeout(0)

      while(true) {
        bind()
      }
    } catch {
      case e: IOException =>
        logger.logError("is shutting down with exception ", e)
        if (!isService) System.exit(1)
    }

    if (!isService) System.exit(0)
  }

  def bind(): Unit = {
    logger.log("is waiting for sparklyr client to connect to port " + port)
    val gatewaySocket = gatewayServerSocket.accept()

    logger.log("accepted connection")
    val buf = new Array[Byte](1024)

    // wait for the end of stdin, then exit
    new Thread("wait for monitor to close") {
      setDaemon(true)
      override def run(): Unit = {
        try {
          val dis = new DataInputStream(gatewaySocket.getInputStream())
          val commandId = dis.readInt()

          logger.log("received command " + commandId)

          GatewayOperattions(commandId) match {
            case GatewayOperattions.GetPorts => {
              val requestedSessionId = dis.readInt()
              val startupTimeout = dis.readInt()

              val dos = new DataOutputStream(gatewaySocket.getOutputStream())

              if (requestedSessionId == sessionId || requestedSessionId == 0)
              {
                logger.log("found requested session matches current session")
                logger.log("is creating backend and allocating system resources")

                val backend = new Backend(logger)
                val backendPort: Int = backend.init(isRemote)

                logger.log("created the backend")

                try {
                  // wait for the end of stdin, then exit
                  new Thread("run backend") {
                    setDaemon(true)
                    override def run(): Unit = {
                      try {
                        dos.writeInt(sessionId)
                        dos.writeInt(gatewaySocket.getLocalPort())
                        dos.writeInt(backendPort)

                        backend.run()
                      }
                      catch {
                        case e: IOException =>
                          logger.logError("failed with exception ", e)

                        if (!isService) System.exit(1)
                      }
                    }
                  }.start()

                  logger.log("is waiting for r process to end")

                  // wait for the end of socket, closed if R process die
                  gatewaySocket.getInputStream().read(buf)
                }
                finally {
                  backend.close()

                  if (!isService) {
                    logger.log("is terminating")
                    System.exit(0)
                  }
                }
              }
              else
              {
                logger.log("is searching for session " + requestedSessionId)

                var portForSession = sessionsMap.get(requestedSessionId)

                var sessionMapRetries: Int = startupTimeout * 10
                while (!portForSession.isDefined && sessionMapRetries > 0)
                {
                  portForSession = sessionsMap.get(requestedSessionId)

                  Thread.sleep(100)
                  sessionMapRetries = sessionMapRetries - 1
                }

                if (portForSession.isDefined)
                {
                  logger.log("found mapping for session " + requestedSessionId)

                  dos.writeInt(requestedSessionId)
                  dos.writeInt(portForSession.get)
                  dos.writeInt(0)
                }
                else
                {
                  logger.log("found no mapping for session " + requestedSessionId)

                  dos.writeInt(requestedSessionId)
                  dos.writeInt(0)
                  dos.writeInt(0)
                }
              }

              dos.close()
            }
            case GatewayOperattions.RegisterInstance => {
              val registerSessionId = dis.readInt()
              val registerGatewayPort = dis.readInt()

              logger.log("received session " + registerSessionId + " registration request")

              val dos = new DataOutputStream(gatewaySocket.getOutputStream())
              dos.writeInt(0)
              dos.flush()
              dos.close()

              sessionsMap += (registerSessionId -> registerGatewayPort)
            }
          }

          gatewaySocket.close()
        } catch {
          case e: IOException =>
            logger.logError("failed with exception ", e)

          if (!isService) System.exit(1)
        }
      }
    }.start()
  }

  def register(gatewayPort: Int, sessionId: Int, port: Int): Boolean = {
    logger.log("is registering session in gateway")

    val s = new Socket(InetAddress.getLoopbackAddress(), gatewayPort)

    val dos = new DataOutputStream(s.getOutputStream())
    dos.writeInt(GatewayOperattions.RegisterInstance.id)
    dos.writeInt(sessionId)
    dos.writeInt(port)

    logger.log("is waiting for registration in gateway")

    val dis = new DataInputStream(s.getInputStream())
    val status = dis.readInt()

    logger.log("finished registration in gateway with status " + status)

    s.close()
    status == 0
  }
}
