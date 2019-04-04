// package geotrellis.server.example.persistence

// import com.azavea.maml.ast.Expression
// import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap
// import geotrellis.server.example._
// import geotrellis.server.vlm.gdal.GDALNode

// import fs2._
// import cats.effect._
// import cats.implicits._
// import org.http4s._
// import org.http4s.server._
// import org.http4s.server.blaze.BlazeServerBuilder
// import org.http4s.server.middleware.{CORS, CORSConfig}
// import org.http4s.syntax.kleisli._
// import com.typesafe.scalalogging.LazyLogging

// import java.util.UUID

// import scala.concurrent.duration._

// object GDALPersistenceServer extends LazyLogging with IOApp {

//   private val corsConfig = CORSConfig(
//     anyOrigin = true,
//     anyMethod = false,
//     allowedMethods = Some(Set("GET")),
//     allowCredentials = true,
//     maxAge = 1.day.toSeconds
//   )

//   private val commonMiddleware: HttpMiddleware[IO] = { (routes: HttpRoutes[IO]) =>
//     CORS(routes)
//   }

//   val stream: Stream[IO, ExitCode] = {
//     for {
//       conf       <- Stream.eval(LoadConf().as[ExampleConf])
//       _          <- Stream.eval(IO { logger.info(s"Initializing persistence demo at ${conf.http.interface}:${conf.http.port}/") })
//       // This hashmap has a [MamlStore] implementation
//       mamlStore = new ConcurrentLinkedHashMap.Builder[UUID, Expression]()
//                     .maximumWeightedCapacity(1000)
//                     .build()
//       mamlPersistence = new PersistenceService[HashMapMamlStore, GDALNode](mamlStore)
//       exitCode   <- BlazeServerBuilder[IO]
//         .enableHttp2(true)
//         .bindHttp(conf.http.port, conf.http.interface)
//         .withHttpApp(Router("/" -> commonMiddleware(mamlPersistence.routes)).orNotFound)
//         .serve
//     } yield exitCode
//   }

//   /** The 'main' method for a cats-effect IOApp */
//   override def run(args: List[String]): IO[ExitCode] =
//     stream.compile.drain.as(ExitCode.Success)
// }
