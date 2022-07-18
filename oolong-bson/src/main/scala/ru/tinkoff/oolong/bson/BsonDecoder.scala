package ru.tinkoff.oolong.bson

import scala.util.*
import scala.util.Try

import magnolia1.*
import org.bson.BsonInvalidOperationException
import org.bson.BsonNull
import org.mongodb.scala.bson.*

import ru.tinkoff.oolong.bson.annotation.*
import ru.tinkoff.oolong.bson.meta.AsQueryMeta
import ru.tinkoff.oolong.bson.meta.QueryMeta

/*
 * A type class that provides a way to produce a value of type `T` from a BsonValue
 */
trait BsonDecoder[T]:
  /*
   * Decode given BsonValue
   */
  def fromBson(value: BsonValue): Try[T]

  /*
   * Create a BsonDecoder that post-processes value with a given function
   */
  def afterRead[U](f: T => U): BsonDecoder[U] =
    (value: BsonValue) => fromBson(value).map(f)

  /*
   * Create a BsonDecoder that post-processes value with a given function
   */
  def afterReadTry[U](f: T => Try[U]): BsonDecoder[U] =
    (value: BsonValue) => fromBson(value).flatMap(f)

object BsonDecoder {

  import scala.compiletime.*
  import scala.deriving.Mirror
  import scala.quoted.*

  def apply[T](using bd: BsonDecoder[T]): BsonDecoder[T] = bd

  /*
   * Create a BsonDecoder from a given function
   */
  def ofDocument[T](f: BsonDocument => Try[T]): BsonDecoder[T] =
    (value: BsonValue) => Try(value.asDocument()).flatMap(f)

  /*
   * Create a BsonDecoder from a given function
   */
  def ofArray[T](f: BsonArray => Try[T]): BsonDecoder[T] =
    (value: BsonValue) => Try(value.asArray()).flatMap(f)

  /*
   * Create a BsonDecoder from a given partial function
   */
  def partial[T](pf: PartialFunction[BsonValue, T]): BsonDecoder[T] =
    (value: BsonValue) =>
      Try(
        pf.applyOrElse[BsonValue, T](
          value,
          bv => throw new BsonInvalidOperationException(s"Can't decode $bv")
        )
      )

  def summonAll[T: Type](using Quotes): List[Expr[BsonDecoder[_]]] =
    import quotes.reflect.*
    Type.of[T] match
      case '[t *: tpes] =>
        Expr.summon[BsonDecoder[t]] match
          case Some(expr) => expr :: summonAll[tpes]
          case _          => derivedImpl[t] :: summonAll[tpes]
      case '[tpe *: tpes] => derivedImpl[tpe] :: summonAll[tpes]
      case '[EmptyTuple]  => Nil

  def toProduct[T: Type](
      mirror: Expr[Mirror.ProductOf[T]],
      elemInstances: List[Expr[BsonDecoder[_]]]
  )(using q: Quotes): Expr[BsonDecoder[T]] =
    import q.reflect.*
    val names        = TypeRepr.of[T].typeSymbol.caseFields.map(_.name)
    val renamingMeta = Expr.summon[QueryMeta[T]]
    val map = renamingMeta match
      case Some(AsQueryMeta(meta)) => meta
      case _                       => Map.empty[String, String]
    '{
      new BsonDecoder[T] {
        def fromBson(value: BsonValue): scala.util.Try[T] = {
          val m = ${ mirror }
          for {
            newValues <- ${ Expr.ofList(elemInstances) }
              .zip(${ Expr(names) })
              .partitionMap { case (instance, name) =>
                for {
                  value <- Option(value.asDocument.getFieldOpt(${ Expr(map) }.getOrElse(name, name)).getOrElse(BsonNull()))
                    .toRight(new RuntimeException(s"Not found value $name"))
                  result <- instance.asInstanceOf[BsonDecoder[Any]].fromBson(value).toEither
                } yield (result.asInstanceOf[AnyRef])
              } match {
              case (Nil, rights)   => Success(rights)
              case (first :: _, _) => Failure(first)
            }
            result <- scala.util.Try(m.fromProduct(Tuple.fromArray(newValues.toArray)))
          } yield result
        }
      }
    }
  end toProduct

  private inline def elemLabels[T <: Tuple]: List[String] = inline erasedValue[T] match {
    case _: EmptyTuple => Nil
    case _: (t *: ts)  => constValue[t].asInstanceOf[String] :: elemLabels[ts]
  }

  def toSum[T: Type](mirror: Expr[Mirror.SumOf[T]], elemInstances: List[Expr[BsonDecoder[_]]])(using
      q: Quotes
  ): Expr[BsonDecoder[T]] =
    import q.reflect.*
    val discriminator = BsonDiscriminator.getAnnotations[T]
    val names         = TypeRepr.of[T].typeSymbol.children.map(_.name).zipWithIndex
    '{
      new BsonDecoder[T] {
        def fromBson(value: BsonValue): scala.util.Try[T] = {
          val m = ${ mirror }
          val (discriminatorField, modifyValue) = ${ discriminator }.headOption
            .map(b => (b.name, b.renameValues))
            .getOrElse(BsonDiscriminator.ClassNameField -> identity[String])

          val result = for {
            descriminatorFromBsonValue <- Option(value.asDocument.get(discriminatorField))
              .map(_.asString)
              .toRight(new RuntimeException(s"Not found discriminator field $discriminatorField"))
              .map(_.getValue)
            index <- ${ Expr(names) }
              .collectFirst {
                case (className, id) if descriminatorFromBsonValue == modifyValue(className) => id
              }
              .toRight(
                new RuntimeException(
                  s"$descriminatorFromBsonValue does not match any of ${${ Expr(names) }.map(v => modifyValue(v._1)).mkString("[", ", ", "]")}"
                )
              )
            result <- ${ Expr.ofList(elemInstances) }
              .apply(index)
              .asInstanceOf[BsonDecoder[Any]]
              .fromBson(value)
              .map(_.asInstanceOf[T])
              .toEither
          } yield result
          result.toTry
        }
      }
    }

  inline given derived[T]: BsonDecoder[T] = ${ derivedImpl[T] }

  def derivedImpl[T: Type](using q: Quotes): Expr[BsonDecoder[T]] =
    import quotes.reflect.*

    val ev: Expr[Mirror.Of[T]] = Expr.summon[Mirror.Of[T]].get

    ev match
      case '{ $m: Mirror.ProductOf[T] { type MirroredElemTypes = elementTypes } } =>
        val elemInstances = summonAll[elementTypes]
        toProduct[T](m, elemInstances)
      case '{ $m: Mirror.SumOf[T] { type MirroredElemTypes = elementTypes } } =>
        val elemInstances = summonAll[elementTypes]
        toSum[T](m, elemInstances)
  end derivedImpl
}
