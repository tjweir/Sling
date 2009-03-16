package net.databinder.sling

import scala.xml.{Unparsed, Elem, NodeSeq}

import slinky.http.servlet.StreamStreamServletApplication
import slinky.http.servlet.StreamStreamServletApplication.resourceOr
import slinky.http.{ContentType, Date, CacheControl}
import slinky.http.StreamStreamApplication._
import slinky.http.request.Request.Stream.{MethodPath, Path}
import slinky.http.request.{Request, GET, RequestHeader}
import slinky.http.response.{Response, OK, NotFound, ETag, NotModified}
import slinky.http.response.xhtml.Doctype.strict
import scalaz.CharSet.UTF8

import dispatch._
import dispatch.couch._
import dispatch.json._
import dispatch.twitter.Search

import org.apache.http.HttpResponse
import org.apache.http.util.EntityUtils

import net.lag.configgy.Configgy

final class App extends StreamStreamServletApplication {
  import App._
  val application =
    (app(_: Request[Stream])) or (req => {
      implicit val r = req
      NotFound(ContentType, content_type) << strict << Page(new TitledContent {
        val title = "Not Found"
        val body = <p> Page not found </p>
      }).html
    })
}

object PageDoc extends Doc {
  val body = 'body ? str
  val tweed = 'tweed ? str
}

object App {
  Configgy.configure("/etc/sling.conf")
  import Js._
  implicit val charSet = UTF8
  def content_type = "text/html; charset=UTF-8"
  
  object DbId {
    def to_path(id: String) = id.replaceAll(" ", "_")
    def to_id(web: String) = web.replaceAll("_", " ")
    val Re = "^/([a-z_]+)/([^/]+)$".r
    def unapply(path: String) = path match {
      case Re(db, id) => Some(Database(db), to_id(id))
      case _ => None
    }
    def apply(db: Database, id: String) = 
      "/" + (db.name :: id :: Nil).mkString("/")
  }
  object Index {
    val Re =  "^/([a-z_]+)/?$".r
    def unapply(path: String) = path match { 
      case Re(db) => Some(Database(db)) 
      case _ => None
    }
  }
  object ComboTag {
    def unapplySeq(tag: String) = tag.replace("-gzip","").replace("\"","").split('|') match { // https://issues.apache.org/bugzilla/show_bug.cgi?id=39727
      case ary if ary.length == 3 || ary.length == 1 => Some(ary)
      case _ => None
    }
    def apply(couch_et: String) = couch_et
    def apply(couch_et: String, tweed: String, latest_id: Number) = 
      '"' + (couch_et.replaceAll("\"", "") :: tweed :: latest_id :: Nil).mkString("|") + '"'
  }
  
  val showdown = new js.showdown()
  
  def couch = Couch(Configgy.config.getString("couch.host","127.0.0.1"))
  
  def cache_heds(ri: HttpResponse, ro: Response[Stream], combo_tag: String) = 
    ro(Date, ri.getFirstHeader(Date).getValue)(CacheControl, "max-age=600")(ETag, combo_tag)
  
  def app(implicit request: Request[Stream]) =
    request match {
      case Path(DbId(db, id)) =>
        val IfNoneMatch = "If-None-Match"
        val (hedded_couch, tweed_js) =
          request.headers.find {
            case (k, v) => k.asString == IfNoneMatch
          } map { _._2.mkString } map {
            case ComboTag(couch_et) =>
              (couch << (IfNoneMatch, "\"" + couch_et + "\""), None)
            case ComboTag(couch_et, tweed, latest) =>
              val res = (new Search)(tweed)
              res.firstOption.filter { case Search.id(id) => println(id); println(latest); id.toString == latest } map { js =>
                (couch << (IfNoneMatch,  "\"" + couch_et+ "\""), Some(res))
              } getOrElse { (couch, Some(res)) }
            case _ => (couch, None)
          } getOrElse (couch, None)

        val couched = db(hedded_couch)
        couched(id) {
          case (OK.toInt, ri, Some(entity)) =>
            val couch_et = ri.getFirstHeader(ETag).getValue
            id match {
              case ("style.css") =>
                Some(cache_heds(ri, OK(ContentType, "text/css; charset=UTF-8"), ComboTag(couch_et)) << 
                  PageDoc.body(Js(entity.getContent())).toList
                )
              case (id) if request !? "edit" =>
                Some(cache_heds(ri, OK(ContentType, content_type), ComboTag(couch_et)) << strict << 
                  Page(EditDocument(TOC(couched, id, "?edit"), 
                    EntityUtils.toString(entity, UTF8)
                  )).html
                )
              case (id) =>
                val js = Js(entity.getContent())
                val PageDoc.body(md) = js
                val (combo_tag, tweedy) = js match {
                  case PageDoc.tweed(t) => 
                    val ljs = tweed_js.getOrElse { (new Search)(t) }
                    val ct: String = ljs.firstOption.map {
                      case Search.id(id) => ComboTag(couch_et, t, id)
                    } getOrElse (ComboTag(couch_et))
                    (ct, Some(t, ljs))
                  case _ => (ComboTag(couch_et), None)
                }
                Some(cache_heds(ri, OK(ContentType, content_type), combo_tag) << strict << 
                  Page(ShowDocument(TOC(couched, id, ""), md, tweedy)).html
                )
            }
          case (NotModified.toInt, ri, _) => Some(cache_heds(ri, NotModified, 
            // wrong etag if tweed present
            ComboTag(ri.getFirstHeader(ETag).getValue)))
          case (NotFound.toInt, _, _) => None 
        }

      case Path(Index(db)) => Some(redirect(DbId(db, db(couch).all_docs.first)))
      case _ => None
    }

  trait Press { val html: Elem }
  
  case class Page(content: Content) extends Press {
    val html =
      <html xmlns="http://www.w3.org/1999/xhtml">
        <head>
          <link rel="stylesheet" href="/css/blueprint/screen.css" type="text/css" media="screen, projection" />
          <link rel="stylesheet" href="/css/blueprint/print.css" type="text/css" media="print" /> 
          <link rel="stylesheet" href="style.css" type="text/css" media="screen, projection" /> 
          { content.head }
        </head>
        <body>
          { content.body }
        </body>
      </html>
  }
  trait Content { def head: NodeSeq; def body: Elem }
  
  trait TitledContent extends Content {
    val title: String
    def head: NodeSeq = <title> { title } </title>
  }
  
  case class TOC(db: Database#H, curr_id: String, query: String) extends Press {
    lazy val html = <h4><ul class="toc">
      {
        db.all_docs map {
          case "style.css" => Nil
          case `curr_id` => <li> { curr_id } </li>
          case id => <li> <a href={ DbId(db, id) + query }>{ id }</a> </li> 
        }
      }
    </ul></h4>
  }

  trait Document extends TitledContent {
    val toc: TOC
    lazy val title = toc.db.name.capitalize + " → " + toc.curr_id
  }
  
  case class ShowDocument(toc: TOC, md: String, tweedy: Option[(String, List[JsValue])]) extends Document {
    def body =
      <div id="content">
        <div class="container">
          <h2>{ title }</h2>
          { toc.html }
          { Unparsed(showdown.makeHtml(md).toString) } 
          {
            tweedy map { case (tweed, js) =>
              <div>
                <h3>{ tweed } tweed</h3>
                <ul class="tweed"> {
                  js map { js =>
                    val Search.text(text) = js
                    val Search.from_user(from) = js
                    val Search.created_at(time) = js
                    val Search.id(id) = js
                    val from_pg = "http://twitter.com/" + from
                    <li>
                      <a href={ from_pg }>{ from }</a>:
                      { Unparsed(text) }
                      <div>
                        <em> { time.replace(" +0000", "") } </em>
                        <a href={ "http://twitter.com/home?status=@" + from + 
                          "%20&in_reply_to_status_id=" + id + "&in_reply_to=" + from
                          }>Reply</a>
                        <a href={ from_pg + "/statuses/" + id }>View Tweet</a>
                      </div>
                    </li>
                  }
                } </ul>
                <p>
                  <a href={ "http://search.twitter.com/search?q=" + tweed }>
                    See all Twitter Search results for { tweed }
                  </a>
                </p>
              </div>
            } toList
          }
        </div>
      </div>
  }
  
  case class EditDocument(toc: TOC, md: String) extends Document {
    override def head = super.head ++ (
      <link rel="stylesheet" href="/css/edit.css" type="text/css" media="screen" /> 
      <script type="text/javascript" src="/script/jquery.js"></script>
      <script type="text/javascript" src="/script/json2.js"></script>
      <script type="text/javascript" src="/js/wmd/showdown.js"></script>
      <script type="text/javascript" src="/js/edit.js"></script>
      <script type="text/javascript"> 
        { Unparsed(
            "var doc = " + md + ";" +
            "var doc_url = '/couch" + DbId(toc.db, toc.curr_id) + "';"
        ) }
      </script>
    )

    def body =
      <div>
        <div id="edit"><div id="fixed"><div>
          <div class="container">
            <form id="form">
              <div><textarea id="body" name="body"></textarea></div>
              <div><input type="submit" value="Save Changes" /></div>
            </form>
          </div>
        </div></div></div>
        <div id="content">
          <div class="container">
            <img id="shade" title="Toggle Editor" src="/css/ship-up.gif" />
            { toc.html }
            <div id="body-preview"></div>
          </div>
        </div>
      </div>
  }
}
