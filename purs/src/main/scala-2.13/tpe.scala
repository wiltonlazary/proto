package purs

import scala.reflect.runtime.universe._

sealed trait Tpe { val tpe: Type; val name: String }
final case class TraitType(tpe: Type, name: String, children: Seq[ChildMeta], firstLevel: Boolean) extends Tpe
final case class RegularType(tpe: Type, name: String) extends Tpe
final case class RecursiveType(tpe: Type, name: String) extends Tpe
final case class NoargsType(tpe: Type, name: String) extends Tpe
final case class TupleType(tpe: Type, name: String, tpe_1: Type, tpe_2: Type) extends Tpe

final case class ChildMeta(name: String, tpe: Type, n: Int, noargs: Boolean, rec: Boolean)

final case class PursType(tmpl: Seq[String], export: Option[String])

final case class Coder(tmpl: String, export: Option[String])

sealed trait IterablePurs
final case class ArrayPurs(tpe: Type) extends IterablePurs
final case class ArrayTuplePurs(tpe1: Type, tpe2: Type) extends IterablePurs