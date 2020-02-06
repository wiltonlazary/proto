package zd.proto.purs

import scala.reflect.runtime.universe._
import scala.reflect.runtime.universe.definitions._
import zd.proto.api.MessageCodec
import zd.gs.z._

object Decoders {
  def from(types: Seq[Tpe], codecs: List[MessageCodec[_]]): Seq[Coder] = {
    types.map{
      case TraitType(tpe, children, true) =>
        val cases = children.map{ case ChildMeta(name, tpe, n, noargs) =>
          if (noargs) s"$n -> decode (decode$name _xs_ pos1) \\_ -> $name"
          else        s"$n -> decode (decode$name _xs_ pos1) $name"
        }
        val name = tpe.typeSymbol.name.encodedName.toString
        val tmpl =
          s"""|decode$name :: Uint8Array -> Decode.Result $name
              |decode$name _xs_ = do
              |  { pos: pos1, val: tag } <- Decode.uint32 _xs_ 0
              |  case tag `zshr` 3 of${cases.map("\n    "+_).mkString("")}
              |    i -> Left $$ Decode.BadType i
              |  where
              |  decode :: forall a. Decode.Result a -> (a -> $name) -> Decode.Result $name
              |  decode res f = map (\\{ pos, val } -> { pos, val: f val }) res""".stripMargin
        Coder(tmpl, s"decode$name".just)
      case TraitType(tpe, children, false) =>
        val name = tpe.typeSymbol.name.encodedName.toString
        val cases = children.flatMap{ case ChildMeta(name, tpe, n, noargs) =>
          if (noargs) s"$n -> decodeFieldLoop end (decode$name _xs_ pos2) \\_ -> notNull $name" :: Nil
          else s"$n -> decodeFieldLoop end (decode$name _xs_ pos2) (notNull <<< $name)" :: Nil
        }
        val tmpl =
          s"""|decode$name :: Uint8Array -> Int -> Decode.Result $name
              |decode$name _xs_ pos0 = do
              |  { pos, val: msglen } <- Decode.uint32 _xs_ pos0
              |  tailRecM3 decode (pos + msglen) null pos
              |    where
              |    decode :: Int -> Nullable $name -> Int -> Decode.Result' (Step { a :: Int, b :: Nullable $name, c :: Int } { pos :: Int, val :: $name })
              |    decode end acc pos1 | pos1 < end = do
              |      { pos: pos2, val: tag } <- Decode.uint32 _xs_ pos1
              |      case tag `zshr` 3 of${cases.map("\n        "+_).mkString}
              |        _ -> decodeFieldLoop end (Decode.skipType _xs_ pos2 $$ tag .&. 7) \\_ -> acc
              |    decode end acc pos1 = nullable acc (Left $$ Decode.MissingFields "$name") \\acc' -> pure $$ Done { pos: pos1, val: acc' }""".stripMargin
        Coder(tmpl, Nothing)
      case TupleType(tpe, tpe_1, tpe_2) =>
        val xs = codecs.find(_.aType == tpe.toString).map(_.nums).getOrElse(throw new Exception(s"codec is missing for ${tpe.toString}"))
        val fun = "decode" + tupleFunName(tpe_1, tpe_2)
        val cases = List(("first", tpe_1, xs("_1")), ("second", tpe_2, xs("_2"))).flatMap((decodeFieldLoop(None) _).tupled)
        val tmpl =
          s"""|$fun :: Uint8Array -> Int -> Decode.Result (Tuple ${pursTypePars(tpe_1)._1} ${pursTypePars(tpe_2)._1})
              |$fun _xs_ pos0 = do
              |  { pos, val: msglen } <- Decode.uint32 _xs_ pos0
              |  { pos: pos1, val } <- tailRecM3 decode (pos + msglen) { ${nothingValue("first", tpe_1)}, ${nothingValue("second", tpe_2)} } pos
              |  case val of
              |    { ${justValue("first", tpe_1)}, ${justValue("second", tpe_2)} } -> pure { pos: pos1, val: Tuple first second }
              |    _ -> Left $$ Decode.MissingFields "$fun"
              |    where
              |    decode :: Int -> { first :: ${pursType(tpe_1)._2}, second :: ${pursType(tpe_2)._2} } -> Int -> Decode.Result' (Step { a :: Int, b :: { first :: ${pursType(tpe_1)._2}, second :: ${pursType(tpe_2)._2} }, c :: Int } { pos :: Int, val :: { first :: ${pursType(tpe_1)._2}, second :: ${pursType(tpe_2)._2} } })
              |    decode end acc pos1 | pos1 < end = do
              |      { pos: pos2, val: tag } <- Decode.uint32 _xs_ pos1
              |      case tag `zshr` 3 of${cases.map("\n        "+_).mkString("")}
              |        _ -> decodeFieldLoop end (Decode.skipType _xs_ pos2 $$ tag .&. 7) (\\_ -> acc)
              |    decode end acc pos1 = pure $$ Done { pos: pos1, val: acc }""".stripMargin
        Coder(tmpl, Nothing)
      case NoargsType(tpe) =>
        val name = tpe.typeSymbol.name.encodedName.toString
        val tmpl =
          s"""|decode$name :: Uint8Array -> Int -> Decode.Result Unit
              |decode$name _xs_ pos0 = do
              |  { pos, val: msglen } <- Decode.uint32 _xs_ pos0
              |  pure { pos: pos + msglen, val: unit }""".stripMargin
        Coder(tmpl, Nothing)
      case RecursiveType(tpe) =>
        val fs = fields(tpe)
        val defObj: String = fs.map{ case (name, tpe, _) => nothingValue(name, tpe) }.mkString("{ ", ", ", " }")
        val justObj: String = fs.map{ case (name, tpe, _) => justValue(name, tpe) }.mkString("{ ", ", ", " }")
        val unObj: String = fs.map{
          case (name,_,_) => name
        }.mkString("{ ", ", ", " }")
        val tmpl =
          if (justObj == unObj) {
            val name = tpe.typeSymbol.name.encodedName.toString
            val cases = fields(tpe).flatMap((decodeFieldLoop(Some(name)) _).tupled)
            s"""|decode$name :: Uint8Array -> Int -> Decode.Result $name
                |decode$name _xs_ pos0 = do
                |  { pos, val: msglen } <- Decode.uint32 _xs_ pos0
                |  tailRecM3 decode (pos + msglen) ($name $defObj) pos
                |    where
                |    decode :: Int -> $name -> Int -> Decode.Result' (Step { a :: Int, b :: $name, c :: Int } { pos :: Int, val :: $name })
                |    decode end acc'@($name acc) pos1 | pos1 < end = do
                |      { pos: pos2, val: tag } <- Decode.uint32 _xs_ pos1
                |      case tag `zshr` 3 of${cases.map("\n        "+_).mkString("")}
                |        _ -> decodeFieldLoop end (Decode.skipType _xs_ pos2 $$ tag .&. 7) \\_ -> acc'
                |    decode end acc pos1 = pure $$ Done { pos: pos1, val: acc }""".stripMargin
          } else {
            val name = tpe.typeSymbol.name.encodedName.toString
            val name1 = tpe.typeSymbol.name.encodedName.toString + "'"
            val cases = fields(tpe).flatMap((decodeFieldLoop(Some(name1)) _).tupled)
            s"""|decode$name :: Uint8Array -> Int -> Decode.Result $name
                |decode$name _xs_ pos0 = do
                |  { pos, val: msglen } <- Decode.uint32 _xs_ pos0
                |  { pos: pos1, val } <- tailRecM3 decode (pos + msglen) ($name1 $defObj) pos
                |  case val of
                |    $name1 $justObj -> pure { pos: pos1, val: $name $unObj }
                |    _ -> Left $$ Decode.MissingFields "$name"
                |    where
                |    decode :: Int -> $name1 -> Int -> Decode.Result' (Step { a :: Int, b :: $name1, c :: Int } { pos :: Int, val :: $name1 })
                |    decode end acc'@($name1 acc) pos1 | pos1 < end = do
                |      { pos: pos2, val: tag } <- Decode.uint32 _xs_ pos1
                |      case tag `zshr` 3 of${cases.map("\n        "+_).mkString("")}
                |        _ -> decodeFieldLoop end (Decode.skipType _xs_ pos2 $$ tag .&. 7) \\_ -> acc'
                |    decode end acc pos1 = pure $$ Done { pos: pos1, val: acc }""".stripMargin
          }
        Coder(tmpl, Nothing)
      case RegularType(tpe) =>
        val fs = fields(tpe)
        val defObj: String = fs.map{ case (name, tpe, _) => nothingValue(name, tpe) }.mkString("{ ", ", ", " }")
        val justObj: String = fs.map{ case (name, tpe, _) => justValue(name, tpe) }.mkString("{ ", ", ", " }")
        val unObj: String = fs.map{
          case (name,_,_) => name
        }.mkString("{ ", ", ", " }")
        val name = tpe.typeSymbol.name.encodedName.toString
        val cases = fields(tpe).flatMap((decodeFieldLoop(Nothing) _).tupled)
        val tmpl =
          if (justObj == unObj)
            s"""|decode$name :: Uint8Array -> Int -> Decode.Result $name
                |decode$name _xs_ pos0 = do
                |  { pos, val: msglen } <- Decode.uint32 _xs_ pos0
                |  tailRecM3 decode (pos + msglen) $defObj pos
                |    where
                |    decode :: Int -> $name -> Int -> Decode.Result' (Step { a :: Int, b :: $name, c :: Int } { pos :: Int, val :: $name })
                |    decode end acc pos1 | pos1 < end = do
                |      { pos: pos2, val: tag } <- Decode.uint32 _xs_ pos1
                |      case tag `zshr` 3 of${cases.map("\n        "+_).mkString("")}
                |        _ -> decodeFieldLoop end (Decode.skipType _xs_ pos2 $$ tag .&. 7) \\_ -> acc
                |    decode end acc pos1 = pure $$ Done { pos: pos1, val: acc }""".stripMargin
          else
            s"""|decode$name :: Uint8Array -> Int -> Decode.Result $name
                |decode$name _xs_ pos0 = do
                |  { pos, val: msglen } <- Decode.uint32 _xs_ pos0
                |  { pos: pos1, val } <- tailRecM3 decode (pos + msglen) $defObj pos
                |  case val of
                |    $justObj -> pure { pos: pos1, val: $unObj }
                |    _ -> Left $$ Decode.MissingFields "$name"
                |    where
                |    decode :: Int -> $name' -> Int -> Decode.Result' (Step { a :: Int, b :: $name', c :: Int } { pos :: Int, val :: $name' })
                |    decode end acc pos1 | pos1 < end = do
                |      { pos: pos2, val: tag } <- Decode.uint32 _xs_ pos1
                |      case tag `zshr` 3 of${cases.map("\n        "+_).mkString("")}
                |        _ -> decodeFieldLoop end (Decode.skipType _xs_ pos2 $$ tag .&. 7) \\_ -> acc
                |    decode end acc pos1 = pure $$ Done { pos: pos1, val: acc }""".stripMargin
        Coder(tmpl, Nothing)
    }.distinct
  }
  
  private[this] def decodeFieldLoop(recursive: Option[String])(name: String, tpe: Type, n: Int): List[String] = {
    def tmpl(n: Int, fun: String, mod: String): List[String] = {
      recursive.cata(
        rec => s"${n} -> decodeFieldLoop end ($fun _xs_ pos2) \\val -> $rec $$ acc { $mod }"
      , s"${n} -> decodeFieldLoop end ($fun _xs_ pos2) \\val -> acc { $mod }"
      ) :: Nil
    }
    if (tpe =:= StringClass.selfType) {
      tmpl(n, "Decode.string", s"$name = notNull val")
    } else if (tpe =:= IntClass.selfType) {
      tmpl(n, "Decode.int32", s"$name = notNull val")
    } else if (tpe =:= BooleanClass.selfType) {
      tmpl(n, "Decode.boolean", s"$name = notNull val")
    } else if (tpe =:= DoubleClass.selfType) {
      tmpl(n, "Decode.double", s"$name = notNull val")
    } else if (tpe.typeConstructor =:= OptionClass.selfType.typeConstructor) {
      val typeArg = tpe.typeArgs.head.typeSymbol
      val tpe1 = typeArg.asType.toType
      if (tpe1 =:= StringClass.selfType) {
        tmpl(n, "Decode.string", s"$name = notNull val")
      } else if (tpe1 =:= IntClass.selfType) {
        tmpl(n, "Decode.int32", s"$name = notNull val")
      } else if (tpe1 =:= BooleanClass.selfType) {
        tmpl(n, "Decode.boolean", s"$name = notNull val")
      } else if (tpe1 =:= DoubleClass.selfType) {
        tmpl(n, "Decode.double", s"$name = notNull val")
      } else {
        val typeArgName = typeArg.name.encodedName.toString
        tmpl(n, s"decode$typeArgName", s"$name = notNull val")
      }
    } else if (isIterable(tpe)) {
      iterablePurs(tpe) match {
        case ArrayPurs(tpe) =>
          if (tpe =:= StringClass.selfType) {
            tmpl(n, "Decode.string", s"$name = snoc acc.$name val")
          } else {
            val tpeName = tpe.typeSymbol.asClass.name.encodedName.toString
            tmpl(n, s"decode$tpeName", s"$name = snoc acc.$name val")
          }
        case ArrayTuplePurs(tpe1, tpe2) =>
          tmpl(n, s"decode${tupleFunName(tpe1, tpe2)}", s"$name = snoc acc.$name val")
      }
    } else {
      val name1 = tpe.typeSymbol.name.encodedName.toString
      tmpl(n, s"decode${name1}", s"$name = notNull val")
    }
  }
}