package zd.proto.purs

import scala.reflect.runtime.universe._
import scala.reflect.runtime.universe.definitions._
import zd.proto.api.MessageCodec
import zd.gs.z._

object Encoders {
  def from(types: Seq[Tpe], codecs: List[MessageCodec[_]]): Seq[Coder] = {
    types.map{
      case TraitType(tpe, name, children, true) =>
        val cases = children.map{ case ChildMeta(name1, _, n, noargs, rec) =>
          if (noargs)
            s"encode$name $name1 = concatAll [ Encode.uint32 ${(n << 3) + 2}, encode$name1 ]" :: Nil
          else {
            val pursTypeName = if (rec) s"$name1''" else name1
            s"encode$name ($pursTypeName x) = concatAll [ Encode.uint32 ${(n << 3) + 2}, encode$name1 x ]" :: Nil
          }
        }
        val tmpl =
          s"""|encode$name :: $name -> Uint8Array
              |${cases.map(_.mkString("\n")).mkString("\n")}""".stripMargin
        Coder(tmpl, s"encode$name".just)
      case TraitType(tpe, name, children, false) =>
        val cases = children.map{ case ChildMeta(name1, _, n, noargs, rec) =>
          if (noargs)
            List(
              s"encode$name $name1 = do"
            , s"  let xs = concatAll [ Encode.uint32 ${(n << 3) + 2}, encode$name1 ]"
            , s"  concatAll [ Encode.uint32 $$ length xs, xs ]"
            ).mkString("\n") :: Nil
          else {
            val pursTypeName = if (rec) s"$name1''" else name1
            List(
              s"encode$name ($pursTypeName x) = do"
            , s"  let xs = concatAll [ Encode.uint32 ${(n << 3) + 2}, encode$name1 x ]"
            , s"  concatAll [ Encode.uint32 $$ length xs, xs ]"
            ).mkString("\n") :: Nil
          }
        }
        val tmpl =
          s"""|encode$name :: $name -> Uint8Array
              |${cases.map(_.mkString("\n")).mkString("\n")}""".stripMargin
        Coder(tmpl, Nothing)
      case TupleType(tpe, tupleName, tpe_1, tpe_2) =>
        val xs = codecs.find(_.aType == tpe.toString).map(_.nums).getOrElse(throw new Exception(s"codec is missing for ${tpe.toString}"))
        val fun = "encode" + tupleName
        val tmpl =
          s"""|$fun :: Tuple ${pursTypePars(tpe_1)._1} ${pursTypePars(tpe_2)._1} -> Uint8Array
              |$fun (Tuple _1 _2) = do
              |  let msg = { _1, _2 }
              |  let xs = concatAll
              |  ${List(("_1", tpe_1, xs("_1"), Nothing), ("_2", tpe_2, xs("_2"), Nothing)).flatMap((encodeField _).tupled).mkString("      [ ",            "\n        , ", "\n        ]")}
              |  concatAll [ Encode.uint32 $$ length xs, xs ]""".stripMargin
        Coder(tmpl, Nothing)
      case NoargsType(tpe, name) =>
        val tmpl =
          s"""|encode$name :: Uint8Array
              |encode$name = Encode.uint32 0""".stripMargin
        Coder(tmpl, Nothing)
      case RecursiveType(tpe, name) =>
        val encodeFields = fields(tpe).flatMap((encodeField _).tupled)
        val tmpl =
          if (encodeFields.nonEmpty) {
            s"""|encode$name :: $name -> Uint8Array
                |encode$name ($name msg) = do
                |  let xs = concatAll
                |  ${encodeFields.mkString("      [ ", "\n        , ", "\n        ]")}
                |  concatAll [ Encode.uint32 $$ length xs, xs ]""".stripMargin
          } else {
            s"""|encode$name :: $name -> Uint8Array
                |encode$name _ = Encode.uint32 0""".stripMargin
          }
        Coder(tmpl, Nothing)
      case RegularType(tpe, name) =>
        val encodeFields = fields(tpe).flatMap((encodeField _).tupled)
        val tmpl =
          if (encodeFields.nonEmpty) {
            s"""|encode$name :: $name -> Uint8Array
                |encode$name msg = do
                |  let xs = concatAll
                |  ${encodeFields.mkString("      [ ", "\n        , ", "\n        ]")}
                |  concatAll [ Encode.uint32 $$ length xs, xs ]""".stripMargin
          } else {
            s"""|encode$name :: $name -> Uint8Array
                |encode$name _ = Encode.uint32 0""".stripMargin
          }
        Coder(tmpl, Nothing)
    }.distinct
  }

  def encodeField(name: String, tpe: Type, n: Int, defval: Maybe[Any]): List[String] = {
    if (tpe =:= StringClass.selfType) {
      List(
        s"""Encode.uint32 ${(n<<3)+2}"""
      , s"""Encode.string msg.$name"""
      )
    } else if (tpe =:= IntClass.selfType) {
      List(
        s"""Encode.uint32 ${(n<<3)+0}"""
      , s"""Encode.uint32 msg.$name"""
      )
    } else if (tpe =:= BooleanClass.selfType) {
      List(
        s"""Encode.uint32 ${(n<<3)+0}"""
      , s"""Encode.boolean msg.$name"""
      )
    } else if (tpe =:= DoubleClass.selfType) {
      List(
        s"""Encode.uint32 ${(n<<3)+1}"""
      , s"""Encode.double msg.$name"""
      )
    } else if (tpe.typeConstructor =:= OptionClass.selfType.typeConstructor) {
      val typeArg = tpe.typeArgs.head.typeSymbol
      val tpe1 = typeArg.asType.toType
      if (tpe1 =:= StringClass.selfType) {
        s"""fromMaybe (fromArray []) $$ map (\\x -> concatAll [ Encode.uint32 ${(n<<3)+2}, Encode.string x ]) msg.$name""" :: Nil
      } else if (tpe1 =:= IntClass.selfType) {
        s"""fromMaybe (fromArray []) $$ map (\\x -> concatAll [ Encode.uint32 ${(n<<3)+0}, Encode.uint32 x ]) msg.$name""" :: Nil
      } else if (tpe1 =:= BooleanClass.selfType) {
        s"""fromMaybe (fromArray []) $$ map (\\x -> concatAll [ Encode.uint32 ${(n<<3)+0}, Encode.boolean x ]) msg.$name""" :: Nil
      } else if (tpe1 =:= DoubleClass.selfType) {
        s"""fromMaybe (fromArray []) $$ map (\\x -> concatAll [ Encode.uint32 ${(n<<3)+1}, Encode.double x ]) msg.$name""" :: Nil
      } else {
        val typeArgName = typeArg.name.encodedName.toString
        s"""fromMaybe (fromArray []) $$ map (\\x -> concatAll [ Encode.uint32 ${(n<<3)+2}, encode$typeArgName x ]) msg.$name""" :: Nil
      }
    } else if (tpe =:= typeOf[Array[Byte]]) {
      List(
        s"""Encode.uint32 ${(n<<3)+2}"""
      , s"""Encode.bytes msg.$name"""
      )
    } else if (isIterable(tpe)) {
      iterablePurs(tpe) match {
        case ArrayPurs(x) =>
          if (x =:= StringClass.selfType) {
            s"""concatAll $$ concatMap (\\x -> [ Encode.uint32 ${(n<<3)+2}, Encode.string x ]) msg.$name""" :: Nil
          } else {
            s"""concatAll $$ concatMap (\\x -> [ Encode.uint32 ${(n<<3)+2}, encode${x.typeSymbol.asClass.name.encodedName.toString} x ]) msg.$name""" :: Nil
          }
        case ArrayTuplePurs(tpe1, tpe2) =>
          s"concatAll $$ concatMap (\\x -> [ Encode.uint32 ${(n<<3)+2}, encode${tupleFunName(tpe1, tpe2)} x ]) msg.$name" :: Nil
      }
    } else {
      val tpeName = tpe.typeSymbol.name.encodedName.toString
      List(
        s"""Encode.uint32 ${(n<<3)+2}"""
      , s"""encode${tpeName} msg.$name"""
      )
    }
  }
}