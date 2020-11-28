package zd
package proto

import proto.api.{MessageCodec, Prepare, N}
import com.google.protobuf.{CodedOutputStream, CodedInputStream}
import scala.quoted._, report._
import scala.collection.immutable.ArraySeq
import zd.proto.Bytes

//todo; optimisation for case object (don't create prepare)
//todo; optimisation for MessageCodec (add .size/.write and use these in proto.api.encode instead of .prepare)
//todo; optimisation for string (write custom .size/.write for string to prevent double time .size computation)
//todo; remove .read exception and rewrite all the protobuf methods that throws exceptions

object macrosapi {

  inline def caseCodecAuto[A]: MessageCodec[A] = ${Macro.caseCodecAuto[A]}
  inline def caseCodecNums[A](inline nums: (String, Int)*): MessageCodec[A] = ${Macro.caseCodecNums[A]('nums)}
  inline def caseCodecIdx[A]: MessageCodec[A] = ${Macro.caseCodecIdx[A]}

  inline def classCodecAuto[A]: MessageCodec[A] = ???
  inline def classCodecNums[A](nums: (String, Int)*)(constructor: Any): MessageCodec[A] = ???

  inline def sealedTraitCodecAuto[A]: MessageCodec[A] = ${Macro.sealedTraitCodecAuto[A]}
  inline def sealedTraitCodecNums[A](nums: (String, Int)*): MessageCodec[A] = ???
  
  inline def enumByN[A]: MessageCodec[A] = ${Macro.enumByN[A]}
}

object Macro {
  def caseCodecAuto[A: Type](using qctx: Quotes): Expr[MessageCodec[A]] = Impl().caseCodecAuto[A]
  def caseCodecNums[A: Type](numsExpr: Expr[Seq[(String, Int)]])(using qctx: Quotes): Expr[MessageCodec[A]] = Impl().caseCodecNums[A](numsExpr)
  def caseCodecIdx[A: Type](using qctx: Quotes): Expr[MessageCodec[A]] = Impl().caseCodecIdx[A]

  def enumByN[A: Type](using qctx: Quotes): Expr[MessageCodec[A]] = Impl().enumByN[A]

  def sealedTraitCodecAuto[A: Type](using qctx: Quotes): Expr[MessageCodec[A]] = Impl().sealedTraitCodecAuto[A]

}

private class Impl(using val qctx: Quotes) extends BuildCodec {
  import qctx.reflect.{_, given}
  import qctx.reflect.defn._

  def caseCodecAuto[A: quoted.Type]: Expr[MessageCodec[A]] = {
    val aType = getCaseClassType[A]
    val aTypeSymbol = aType.typeSymbol
    val typeName = aTypeSymbol.fullName
    val params: List[Symbol] = aTypeSymbol.caseClassValueParams
    val nums: List[(String, Int)] = params.map(p =>
      p.annots.collect{ case Apply(Select(New(tpt),_), List(Literal(Constant.Int(num)))) if tpt.tpe.isNType => p.name -> num } match {
        case List(x) => x
        case Nil => throwError(s"missing ${NTpe.typeSymbol.name} annotation for `${typeName}`")
        case _ => throwError(s"multiple ${NTpe.typeSymbol.name} annotations applied for `${typeName}`")
      }
    )
    messageCodec(aType, nums, params, restrictDefaults=true)
  }

  def caseCodecNums[A: quoted.Type](numsExpr: Expr[Seq[(String, Int)]]): Expr[MessageCodec[A]] = {
    val nums: Seq[(String, Int)] = numsExpr match {
      case Varargs(argExprs) =>
        argExprs.collect{
          case '{ ($x:String, $y:Int) } => Term.of(x) -> Term.of(y)
          case '{ ($x:String) -> ($y:Int) } => Term.of(x) -> Term.of(y)
        }.collect{
          case (Literal(Constant.String(name)), Literal(Constant.Int(num))) => name -> num
        }
      case _ => Seq()
    }
    val aType = getCaseClassType[A]
    val aTypeSymbol = aType.typeSymbol
    val params: List[Symbol] = aTypeSymbol.caseClassValueParams
    messageCodec(aType, nums, params, restrictDefaults=true)
  }

  def caseCodecIdx[A: quoted.Type]: Expr[MessageCodec[A]] = {
    val aType = getCaseClassType[A]
    val aTypeSymbol = aType.typeSymbol
    val params: List[Symbol] = aTypeSymbol.caseClassValueParams
    val nums: List[(String, Int)] = params.zipWithIndex.map{case (p, idx) => (p.name, idx + 1) }
    messageCodec(aType, nums, params, restrictDefaults=false)
  }

  def messageCodec[A: quoted.Type](aType: TypeRepr, nums: Seq[(String, Int)], cParams: List[Symbol], restrictDefaults: Boolean): Expr[MessageCodec[A]] = {
    val aTypeSym = aType.typeSymbol
    val aTypeCompanionSym = aTypeSym.companionModule
    val typeName = aTypeSym.fullName
    
    if (nums.exists(_._2 < 1)) throwError(s"nums ${nums} should be > 0")
    if (nums.size != cParams.size) throwError(s"nums size ${nums} not equal to `${typeName}` constructor params size ${cParams.size}")
    if (nums.groupBy(_._2).exists(_._2.size != 1)) throwError(s"nums ${nums} should be unique")
    val restrictedNums = aType.restrictedNums
    val typeArgsToReplace: Map[String, TypeRepr] = aType.typeArgsToReplace

    val fields: List[FieldInfo] = cParams.zipWithIndex.map{ case (s, i) =>
      val (name, tpe) = s.tree match  
        case ValDef(v_name, v_tpt, v_rhs) => 
          typeArgsToReplace.get(v_tpt.tpe.typeSymbol.name) match
            case Some(typeArg) => (v_name, typeArg)
            case None => (v_name, v_tpt.tpe)
        case _ => throwError(s"wrong param definition of case class `${typeName}`")
      
      val defaultValue: Option[Term] = aTypeCompanionSym.method(defaultMethodName(i)) match
        case List(x) =>
          if tpe.isOption && restrictDefaults then throwError(s"`${name}: ${tpe.typeSymbol.fullName}`: default value for Option isn't allowed")
          else if tpe.isIterable && restrictDefaults then throwError(s"`${name}: ${tpe.typeSymbol.fullName}`: default value for collections isn't allowed")
          else Some(Select(Ref(aTypeCompanionSym), x))
        case _ => None
      val num: Int =
        nums.collectFirst{ case (name1, num1) if name1 == name =>
          if restrictedNums.contains(num1) then throwError(s"num ${num1} for `${typeName}` is restricted") 
          else num1
        }.getOrElse{
          throwError(s"missing num for `${name}: ${typeName}`")
        }
      FieldInfo(
        name = name
      , num = num
      , tpe = tpe
      , getter = (a: Term) => Select.unique(a, name)
      , sizeSym = Symbol.newVal(Symbol.spliceOwner, s"${name}Size", TypeRepr.of[Int], Flags.Mutable, Symbol.noSymbol)
      , prepareSym = Symbol.newVal(Symbol.spliceOwner, s"${name}Prepare", PrepareType, Flags.Mutable, Symbol.noSymbol)
      , prepareOptionSym = Symbol.newVal(Symbol.spliceOwner, s"${name}Prepare", OptionType.appliedTo(PrepareType), Flags.Mutable, Symbol.noSymbol)
      , prepareArraySym = Symbol.newVal(Symbol.spliceOwner, s"${name}Prepare", TypeRepr.of[Array[Prepare]], Flags.Mutable, Symbol.noSymbol)
      , defaultValue = defaultValue
      )
    }
    '{ 
      new MessageCodec[A] {
        def prepare(a: A): Prepare = ${ prepareImpl('a, fields) }
        def read(is: CodedInputStream): A = ${ readImpl(aType, fields, 'is).asExprOf[A] }
      }
    }
  }

  def enumByN[A: Type]: Expr[MessageCodec[A]] = {
    val a_tpe = TypeRepr.of[A]
    val a_typeSym = a_tpe.typeSymbol
    val typeName = a_typeSym.fullName
    val xs = a_typeSym.children
    val restrictedN: List[Int] = a_tpe.restrictedNums
    xs.map{ x =>
      val num: Int =
        x.annots.collect{
          case Apply(Select(New(tpt),_), List(Literal(Constant.Int(num1)))) if tpt.tpe.isNType => num1
        } match {
          case List(num1) if restrictedN.contains(num1) => throwError(s"num ${num1} for `${typeName}` is restricted") 
          case List(num1) => num1
          case Nil => throwError(s"missing ${NTpe.typeSymbol.name} annotation for `${typeName}`")
          case _ => throwError(s"multiple ${NTpe.typeSymbol.name} annotations applied for `${typeName}`")
        }
      // val field = FieldInfo(
      //   name = s"field${num}"
      // , num = num
      // , tpe = ???
      // , tpt = ???
      // , getter = aTypeSym //?
      // , sizeSym = Symbol.newVal(ctx.owner, s"field${num}Size", IntType, Flags.Mutable, Symbol.noSymbol)
      // , prepareSym = Symbol.newVal(ctx.owner, s"field${num}Prepare", PrepareType, Flags.Mutable, Symbol.noSymbol)
      // , prepareOptionSym = Symbol.newVal(ctx.owner, s"field${num}Prepare", appliedOptionType(PrepareType), Flags.Mutable, Symbol.noSymbol)
      // , prepareArraySym = Symbol.newVal(ctx.owner, s"field${num}Prepare", typeOf[Array[Prepare]], Flags.Mutable, Symbol.noSymbol)
      // , defaultValue = None
      // )
      // prepareImpl(x, List(field))
    }
    '{
      new MessageCodec[A] {
        def prepare(a: A): Prepare = ???
        def read(is: CodedInputStream): A = ???
      }
    }
  }

  def sealedTraitCodecAuto[A: quoted.Type]: Expr[MessageCodec[A]] = {
    val aType = getSealedTrait[A]
    val aTypeSymbol = aType.typeSymbol
    val typeName = aTypeSymbol.fullName
    val xs = aTypeSymbol.children
    val nums: List[(TypeRepr, Int)] = xs.map{ x =>
      x.annots.collect{ case Apply(Select(New(tpt),_), List(Literal(Constant.Int(num)))) if tpt.tpe.isNType => x.tpe -> num } match
        case List(x) => x
        case Nil => throwError(s"missing ${NTpe.typeSymbol.name} annotation for `${typeName}`")
        case _ => throwError(s"multiple ${NTpe.typeSymbol.name} annotations applied for `${typeName}`")
    }
    sealedTraitCodec(aType, nums)
  }

  def sealedTraitCodec[A: quoted.Type](aType: TypeRepr, nums: Seq[(TypeRepr, Int)]): Expr[MessageCodec[A]] = {
    val aTypeSymbol = aType.typeSymbol
    val typeName = aTypeSymbol.fullName
    val subclasses = aTypeSymbol.children

    if (subclasses.size <= 0) throwError(s"required at least 1 subclass for `${typeName}`")
    if (nums.size != subclasses.size) throwError(s"`${typeName}` subclasses ${subclasses.size} count != nums definition ${nums.size}")
    if (nums.exists(_._2 < 1)) throwError(s"nums for ${typeName} should be > 0")
    if (nums.groupBy(_._2).exists(_._2.size != 1)) throwError(s"nums for ${typeName} should be unique")
    val restrictedNums = aType.restrictedNums

    val fields: List[FieldInfo] = subclasses.map{ s =>
      val tpe = s.tpe
      val num: Int = nums.collectFirst{ case (tpe1, num) if tpe =:= tpe1 => num }.getOrElse(throwError(s"missing num for class `${tpe}` of trait `${aType}`"))
      if (restrictedNums.contains(num)) throwError(s"num ${num} is restricted for class `${tpe}` of trait `${aType}`")
    
      FieldInfo(
        name = s.fullName
      , num = num
      , tpe = tpe
      , getter = 
          if s.isTerm then
            (a: Term) => Ref(s)
          else
            (a: Term) => Select.unique(a, "asInstanceOf").appliedToType(tpe)
      , sizeSym = Symbol.newVal(Symbol.spliceOwner, s"field${num}Size", TypeRepr.of[Int], Flags.Mutable, Symbol.noSymbol)
      , prepareSym = Symbol.newVal(Symbol.spliceOwner, s"field${num}Prepare", PrepareType, Flags.Mutable, Symbol.noSymbol)
      , prepareOptionSym = Symbol.noSymbol
      , prepareArraySym = Symbol.noSymbol
      , defaultValue = None
      )
    }
    '{
      new MessageCodec[A] {
        def prepare(a: A): Prepare = ${ prepareTrait('a, fields) }
        def read(is: CodedInputStream): A = ${ readImpl(aType, fields, 'is).asExprOf[A] }
      }
    }
  }

  private def getSealedTrait[A: Type]: TypeRepr = {
    val tpe = TypeRepr.of[A]
    if tpe.isSealedTrait then tpe else throwError(s"`${tpe.typeSymbol.fullName}` is not a sealed trait. Make sure that you specify codec type explicitly.\nExample:\n implicit val codecName: MessageCodec[SealedTraitTypeHere] = ...\n\n")
  }

  private def getCaseClassType[A: Type]: TypeRepr = {
    val tpe = TypeRepr.of[A]
    if tpe.isCaseType then tpe else throwError(s"`${tpe.typeSymbol.fullName}` is not a case class")
  }

}