package zd
package proto

import org.scalatest.{FreeSpec, Matchers}
import zd.proto.api.N

class PurescriptSpec extends FreeSpec with Matchers {
  "purs has" - {
    val res = Purescript.generate[Push, Pull](moduleName="Api")
    "module name" in {
      res.prelude.startsWith("module Api")
    }
    "data type" in {
      res.decodeData should be ("data Push = SiteOpts SiteOpts")
      res.encodeData should be ("data Pull = GetSitePermissions GetSitePermissions | GetSites GetSites")
      ()
    }
    "types" in {
      res.decodeTypes.toSet should be (Set(
        "type SiteOpt = { id :: String, label :: String }",
        "type SiteOpts = { xs :: Array SiteOpt }",
      ))
      res.encodeTypes.toSet should be (Set(
        "type GetSites = {  }",
        "type GetSitePermissions = { siteId :: String }",
      ))
      ()
    }
    "decoders" in {
      assert(res.decoders(0).contains("decodePush"))
      assert(res.decoders(0).contains("1 -> do"))
      assert(res.decoders(0).contains("decodeSiteOpts reader msglen"))
      assert(res.decoders(0).contains("SiteOpts x"))

      assert(res.decoders(1).contains("decodeSiteOpt"))
      assert(res.decoders(1).contains("""{ id: "", label: "" }"""))
      assert(res.decoders(1).contains("2 -> do"))
      assert(res.decoders(1).contains("string reader"))
      assert(res.decoders(1).contains("acc { id = x }"))
      assert(res.decoders(1).contains("acc { label = x }"))

      assert(res.decoders(2).contains("decodeSiteOpts"))
      assert(res.decoders(2).contains("{ xs: [] }"))
      assert(res.decoders(2).contains("uint32 reader >>= decodeSiteOpt reader"))
      assert(res.decoders(2).contains("acc { xs = snoc acc.xs x }"))
    }
    "print" in {
      println(res.format)
    }
  }
}

sealed trait Push
@N(1) final case class SiteOpts(@N(1) xs: Stream[SiteOpt]) extends Push

final case class SiteOpt(@N(1) id: String, @N(2) label: String)

sealed trait Pull
@N(1) final case class GetSites() extends Pull
@N(2) final case class GetSitePermissions(@N(1) siteId: String) extends Pull
