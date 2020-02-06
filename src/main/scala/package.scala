package zd.proto

import scala.reflect.runtime.universe._
import scala.reflect.runtime.universe.definitions._
import scala.annotation.tailrec
import zd.gs.z._

package object purs {
  def pursTypePars(tpe: Type): (String, String) = {
    if (tpe =:= StringClass.selfType) {
      "String" -> s"(Nullable String)"
    } else if (tpe =:= IntClass.selfType) {
      "Int" -> s"(Nullable Int)"
    } else if (tpe =:= BooleanClass.selfType) {
      "Boolean" -> s"(Nullable Boolean)"
    } else if (tpe =:= DoubleClass.selfType) {
      "Number" -> s"(Nullable Number)"
    } else if (tpe =:= typeOf[Array[Byte]]) {
      "Uint8Array" -> s"(Nullable Uint8Array)"
    } else if (tpe.typeConstructor =:= OptionClass.selfType.typeConstructor) {
      val typeArg = tpe.typeArgs.head
      if (typeArg =:= DoubleClass.selfType) {
        s"(Nullable Number)" -> s"(Nullable Number)"
      } else {
        val name = typeArg.typeSymbol.name.encodedName.toString
        s"(Nullable $name)" -> s"(Nullable $name)"
      }
    } else if (isIterable(tpe)) {
      iterablePurs(tpe) match {
        case ArrayPurs(tpe) =>
          val name = tpe.typeSymbol.asClass.name.encodedName.toString
          s"(Array $name)" -> s"(Array $name)"
        case ArrayTuplePurs(tpe1, tpe2) =>
          val name1 = pursTypePars(tpe1)._1
          val name2 = pursTypePars(tpe2)._1
          s"(Array (Tuple $name1 $name2))" -> s"(Array (Tuple $name1 $name2))"
      }
    } else {
      val name = tpe.typeSymbol.name.encodedName.toString
      name -> s"(Nullable $name)"
    }
  }
  
  def pursType(tpe: Type): (String, String) = {
    def trim(x: String): String = x.stripPrefix("(").stripSuffix(")")
    val (a, b) = pursTypePars(tpe)
    trim(a) -> trim(b)
  }

  def isIterable(tpe: Type): Boolean = {
    tpe.baseClasses.exists(_.asType.toType.typeConstructor <:< typeOf[scala.collection.Iterable[Unit]].typeConstructor)
  }
  
  def tupleFunName(tpe_1: Type, tpe_2: Type): String = {
    (pursType(tpe_1)._1.filter(_.isLetter) + "_" + pursType(tpe_2)._1).filter(_.isLetter)
  }
  
  def nothingValue(name: String, tpe: Type): String = {
    if (isIterable(tpe)) {
      iterablePurs(tpe) match {
        case _: ArrayPurs => s"$name: []"
        case _: ArrayTuplePurs => s"$name: []"
      }
    } else s"$name: null"
  }
  
  def justValue(name: String, tpe: Type): String = {
    if (tpe.typeConstructor =:= OptionClass.selfType.typeConstructor) name
    else if (isIterable(tpe)) name
    else {
      s"$name: notNull $name"
    }
  }

  def iterablePurs(tpe: Type): IterablePurs = {
    tpe.typeArgs match {
      case x :: Nil => ArrayPurs(x)
      case x :: y :: Nil => ArrayTuplePurs(x, y)
      case _ => throw new Exception(s"too many type args for $tpe")
    }
  }

  def collectTpes(tpe: Type): Seq[Tpe] = {
    val complexType: Type => Boolean = {
      case tpe if tpe =:= StringClass.selfType => false
      case tpe if tpe =:= IntClass.selfType => false
      case tpe if tpe =:= BooleanClass.selfType => false
      case tpe if tpe =:= DoubleClass.selfType => false
      case tpe if tpe =:= typeOf[Array[Byte]] => false
      case _ => true
    }
    @tailrec def isRecursive(base: Type, compareTo: List[Type]): Boolean = compareTo match {
      case Nil => false
      case x :: _ if x =:= base => true
      case x :: xs => isRecursive(base, x.typeArgs.map(_.typeSymbol.asType.toType) ++ xs)
    }
    def isTrait(t: Type): Boolean = {
      t.typeSymbol.isClass && t.typeSymbol.asClass.isTrait && t.typeSymbol.asClass.isSealed
    }
    def findChildren(tpe: Type): Seq[ChildMeta] = {
      tpe.typeSymbol.asClass.knownDirectSubclasses.toVector.map(x => x -> findN(x)).collect{ case (x, Some(n)) => x -> n }.sortBy(_._2).map{
        case (x, n) =>
          val tpe = x.asType.toType
          ChildMeta(name=tpe.typeSymbol.name.encodedName.toString, tpe, n, noargs=fields(tpe).isEmpty)
      }
    }
    @tailrec def loop(head: Type, tail: Seq[Type], acc: Seq[Tpe], firstLevel: Boolean): Seq[Tpe] = {
      val (tail1, acc1): (Seq[Type], Seq[Tpe]) =
        if (acc.exists(_.tpe =:= head)) {
          (tail, acc)
        } else if (isTrait(head)) {
          val children = findChildren(head)
          (children.map(_.tpe)++tail, acc:+TraitType(head, children, firstLevel))
        } else if (head.typeConstructor =:= OptionClass.selfType.typeConstructor) {
          val typeArg = head.typeArgs.head.typeSymbol
          val typeArgType = typeArg.asType.toType
          if (complexType(typeArgType)) (typeArgType+:tail, acc)
          else (tail, acc)
        } else if (isIterable(head)) {
          head.typeArgs match {
            case x :: Nil =>
              val typeArg = x.typeSymbol
              val typeArgType = typeArg.asType.toType
              if (complexType(typeArgType)) (typeArgType+:tail, acc)
              else (tail, acc)
            case x :: y :: Nil =>
              val zs = List(x, y).filter(complexType)
              (zs++tail, acc:+TupleType(appliedType(typeOf[Tuple2[Unit, Unit]].typeConstructor, x, y), x, y))
            case _ => throw new Exception(s"too many type args for ${head}")
          }
        } else {
          val xs = fields(head).map(_._2)
          val ys = xs.filter(complexType)
          val z =
            if (xs.isEmpty) NoargsType(head)
            else if (isRecursive(head, ys)) RecursiveType(head)
            else RegularType(head)
          (ys++tail, acc:+z)
        }
      tail1 match {
        case h +: t => loop(h, t, acc1, false)
        case _ => acc1
      }
    }
    loop(tpe, Nil, Nil, true)
  }

  private[this] def findN(x: Symbol): Option[Int] = {
    x.annotations.filter(_.tree.tpe =:= typeOf[zd.proto.api.N])  match {
      case List(x1) => x1.tree.children.tail match {
        case List(Literal(Constant(n: Int))) => Some(n)
        case _ => throw new Exception("bad args in N")
      }
      case Nil => None
      case _ => throw new Exception(s"multiple N on ${x}")
    }
  }
  
  def fields(tpe: Type): List[(String, Type, Int)] = {
    tpe.typeSymbol.asClass.primaryConstructor.asMethod.paramLists.flatten.map{ x =>
      val term = x.asTerm
      (term.name.encodedName.toString, term.info, findN(x))
    }.collect{ case (a, b, Some(n)) => (a, b, n) }.sortBy(_._3)
  }

  def makePursTypes(types: Seq[Tpe], genMaybe: Boolean): Seq[PursType] = {
    types.flatMap{
      case TraitType(tpe, children, true) =>
        val name = tpe.typeSymbol.name.encodedName.toString
        List(PursType(List(
          s"data $name = ${children.map{
            case x if x.noargs => x.name
            case x => s"${x.name} ${x.name}"
          }.mkString(" | ")}"
        ), export=s"$name(..)".just))
      case TraitType(tpe, children, false) =>
        val name = tpe.typeSymbol.name.encodedName.toString
        List(PursType(List(
          s"data $name = ${children.map{
            case x if x.noargs => x.name
            case x => s"${x.name} ${x.name}"
          }.mkString(" | ")}"
        , s"derive instance eq$name :: Eq $name"
        ), export=s"$name(..)".just))
      case _: TupleType => Nil
      case _: NoargsType => Nil
      case RecursiveType(tpe) =>
        val name = tpe.typeSymbol.name.encodedName.toString
        val fs = fields(tpe).map{ case (name1, tpe, _) => name1 -> pursType(tpe) }
        val params = fs.map{ case (name1, tpe) => s"$name1 :: ${tpe._1}" }.mkString(", ")
        val x = s"newtype $name = $name { $params }"
        val eq = s"derive instance eq$name :: Eq $name"
        if (genMaybe) {
          val params1 = fs.map{ case (name1, tpe) => s"$name1 :: ${tpe._2}" }.mkString(", ")
          if (params == params1) {
            List(PursType(List(x, eq), s"$name($name)".just))
          } else {
            val x1 = s"newtype $name' = $name' { $params1 }"
            List(PursType(List(x, eq), s"$name($name)".just), PursType(List(x1), Nothing))
          }
        } else {
          List(PursType(List(x, eq), s"$name($name)".just))
        }
      case RegularType(tpe) =>
        val name = tpe.typeSymbol.name.encodedName.toString
        val fs = fields(tpe).map{ case (name1, tpe, _) => name1 -> pursType(tpe) }
        val params = fs.map{ case (name1, tpe) => s"$name1 :: ${tpe._1}" }.mkString(", ")
        val x = if (params.nonEmpty) s"type $name = { $params }" else s"type $name = {}"
        if (genMaybe) {
          val params1 = fs.map{ case (name1, tpe) => s"$name1 :: ${tpe._2}" }.mkString(", ")
          if (params == params1) {
            Seq(PursType(Seq(x), s"$name".just))
          } else {
            val x1 = s"type $name' = { $params1 }"
            Seq(PursType(Seq(x), s"$name".just), PursType(Seq(x1), Nothing))
          }
        } else {
          Seq(PursType(Seq(x), s"$name".just))
        }
    }.distinct
  }
}