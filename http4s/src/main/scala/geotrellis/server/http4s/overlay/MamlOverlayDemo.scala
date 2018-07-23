package geotrellis.server.http4s.overlay

import geotrellis.server.core.maml._
import MamlStore.ops._
import MamlReification.ops._

import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap
import com.azavea.maml.util.Vars
import com.azavea.maml.ast._
import com.azavea.maml.ast.codec.tree._
import com.azavea.maml.eval._
import org.http4s._
import org.http4s.dsl.Http4sDsl
import org.http4s.circe._
import io.circe._
import io.circe.parser._
import io.circe.syntax._
import cats._
import cats.data._, Validated._
import cats.implicits._
import cats.effect._
import com.typesafe.scalalogging.LazyLogging
import geotrellis.raster._
import geotrellis.raster.render._

import scala.math._
import java.net.URI
import java.util.{UUID, NoSuchElementException}
import scala.util.Try
import scala.collection.mutable


class MamlOverlayDemo(
  interpreter: BufferingInterpreter = BufferingInterpreter.DEFAULT
)(implicit t: Timer[IO]) extends Http4sDsl[IO] with LazyLogging {

  // Unapply to handle UUIDs on path
  object IdVar {
    def unapply(str: String): Option[UUID] = {
      if (!str.isEmpty)
        Try(UUID.fromString(str)).toOption
      else
        None
    }
  }

  implicit val expressionDecoder = jsonOf[IO, Map[String, OverlayDefinition]]

  def produceMaml(defs: Map[String, OverlayDefinition]): Expression = {
    val weighted: List[Expression] = defs.map({ case(id, overlayDefinition) =>
      Multiplication(List(RasterVar(id.toString), DblLit(overlayDefinition.weight)).toList)
    }).toList
    Addition(weighted)
  }

  val demoStore: ConcurrentLinkedHashMap[UUID, Json] = new ConcurrentLinkedHashMap.Builder[UUID, Json]()
    .maximumWeightedCapacity(100)
    .build();

  def routes: HttpService[IO] = HttpService[IO] {
    case req @ POST -> Root / IdVar(key) =>
      (for {
         args <- req.as[Map[String, OverlayDefinition]]
         _    <- req.bodyAsText.compile.toList flatMap { reqBody =>
                   IO.pure(logger.info(s"Attempting to store expression (${reqBody.mkString("")}) at key ($key)"))
                 }
         _    <- IO.pure { demoStore.put(key, args.asJson) }
      } yield ()).attempt flatMap {
        case Right(_) =>
          Created()
        case Left(InvalidMessageBodyFailure(_, _)) | Left(MalformedMessageBodyFailure(_, _)) =>
          req.bodyAsText.compile.toList flatMap { reqBody =>
            BadRequest(s"""Unable to parse ${reqBody.mkString("")} as a MAML expression""")
          }
        case Left(err) =>
          logger.debug(err.toString, err)
          InternalServerError(err.toString)
      }

    case req @ GET -> Root / IdVar(key) / IntVar(z) / IntVar(x) / IntVar(y) =>
      (for {
        // Get arguments
        paramMap   <- IO.pure { Option(demoStore.get(key)).flatMap(_.as[Map[String,OverlayDefinition]].toOption).get }
                        .recoverWith({ case _: NoSuchElementException => throw MamlStore.ExpressionNotFound(key) })
        _          <- IO.pure(logger.info(s"Retrieved parameters for TMS ($z, $x, $y): ${paramMap.asJson.noSpaces}"))
        expr       <- IO.pure { produceMaml(paramMap) }
        _          <- IO.pure(logger.info(s"Constructed MAML at TMS ($z, $x, $y): ${expr.asJson.noSpaces}"))
        vars       <- IO.pure { Vars.varsWithBuffer(expr) }
        params     <- vars.toList.parTraverse { case (varName, (_, buffer)) =>
                        paramMap(varName).tmsReification(buffer)(t)(z, x, y).map(varName -> _)
                      } map { _.toMap }
        reified    <- IO.pure { Expression.bindParams(expr, params) }
      } yield reified.andThen(interpreter(_)).andThen(_.as[Tile])).attempt flatMap {
        case Right(Valid(tile)) =>
          Ok(tile.renderPng(ColorRamps.Viridis).bytes)
        case Right(Invalid(errs)) =>
          logger.debug(errs.toList.toString)
          BadRequest(errs.asJson)
        case Left(MamlStore.ExpressionNotFound(err)) =>
          logger.info(err.toString)
          NotFound()
        case Left(err) =>
          logger.debug(err.toString, err)
          InternalServerError(err.toString)
      }
  }
}

