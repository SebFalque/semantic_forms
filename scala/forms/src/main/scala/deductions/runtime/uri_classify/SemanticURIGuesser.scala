package deductions.runtime.uri_classify

import java.net.URL
import akka.http.model._
import akka.actor.ActorSystem
import HttpMethods._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import akka.http.engine.parsing.HttpHeaderParser
import akka.http.model.HttpHeader

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.async.Async.{ async, await }
import akka.io.IO
import akka.util.Timeout
import akka.http.Http
import akka.actor.{ ActorSystem }
import akka.http.model.{ HttpMethods, HttpEntity, HttpRequest, HttpResponse, Uri }
import akka.stream.{ FlowMaterializer }
import akka.stream.scaladsl.Flow
import akka.pattern.ask
import HttpEntity._
import com.typesafe.config.ConfigFactory
import com.typesafe.config.Config
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.Sink
import akka.http.model._
import akka.http.model.HttpMethods._
import java.net.URL

/* classify URI (non-blocking): leveraging on MIME types in HTTP headers.
MIME categories :
text (including HTML), 
image,
sound, 
video,
xml, and other machine processable stuff
rdf, Json-LD, Turtle and other semantic content 
 */
object SemanticURIGuesser {
  
  sealed abstract class SemanticURIType {
    override def toString = getClass.getSimpleName
  }
  object SemanticURI extends SemanticURIType
  object Application extends SemanticURIType
  object Audio extends SemanticURIType
  object Image extends SemanticURIType
  object Message extends SemanticURIType
  object Multipart extends SemanticURIType
  object Text extends SemanticURIType
  object Video extends SemanticURIType
  object Data extends SemanticURIType
  object Unknown extends SemanticURIType
  
  def guessSemanticURIType(url: String) : Future[SemanticURIType]= {
    val response: Future[HttpResponse] = HttpClient.makeRequest(url, HEAD)
    println("response " + response)
    response.map { resp => semanticURITypeFromHeaders(resp, url) }
  }

  private def semanticURITypeFromHeaders(
    resp: HttpResponse,
    url: String): SemanticURIType = {
    val headers: Seq[HttpHeader] = resp.headers
    println("headers " + headers)
    type ContentType = akka.http.model.headers.`Content-Type`
    val contentType = resp.header[ContentType]
    println("semanticURITypeFromHeaders: contentType " + contentType)
    val semanticURIType: SemanticURIType =
      contentType match { 
      case Some(header) =>
        println("header " + header)
        val mediaType = header.contentType.mediaType
        val semanticURIType: SemanticURIType = makeSemanticURIType(mediaType, url)
        if (semanticURIType == Unknown)
          makeSemanticURITypeFromSuffix(url)
        else semanticURIType
      case None => makeSemanticURITypeFromSuffix(url)
    }
    println("semanticURITypeFromHeaders: semanticURIType " + semanticURIType)
    semanticURIType 
  }

  private def makeSemanticURITypeFromSuffix(url: String) : SemanticURIType = {
    val mimeTypeFromSuffix = trySuffix(url)
    println( "makeSemanticURITypeFromSuffix: mimeTypeFromSuffix " + mimeTypeFromSuffix )
    if (mimeTypeFromSuffix == null)
      Unknown
    else {
      val mediaTypeFromSuffix = MediaType.custom(mimeTypeFromSuffix)
      println( "makeSemanticURITypeFromSuffix: mediaTypeFromSuffix " + mediaTypeFromSuffix )
      makeSemanticURIType(mediaTypeFromSuffix, url)
    }
  }
  
  private def makeSemanticURIType(mt:MediaType, url: String) = {
    mt match {
      case mt if mt.isApplication => guessFromHeader(mt, url, Application)
      case mt if mt.isAudio => Audio
      case mt if mt.isImage => Image
      case mt if mt.isMessage => Data
      case mt if mt.isMultipart => Data
      case mt if mt.isText => guessFromHeader(mt, url, Text)
      case mt if mt.isVideo => Video
      case _ => Unknown
    }
  }
  
  /** take in account suffix (jpg, etc) ;
   *  TODO: works in the REPL, but not in TestSemanticURIGuesser */
  private def trySuffix(url: String) : String = {
    import java.nio.file._
    import java.net._
    val urlObj = new URL(url)
    println( "trySuffix " + url )
    // to eliminate #me in FOAF profiles: 
    val source = Paths.get(urlObj.getFile)
    val source2 = source.toString()

    val mimeType = URLConnection.guessContentTypeFromName(url)
    val mimeType2 = mimeType match {
      case null => source2 match {
        // TODO : do not use strings but an API
        case _ if( source2.endsWith(".rdf") ) => "application/rdf+xml"
        case _ if( source2.endsWith(".owl") ) => "application/owl+xml"
        case _ if( source2.endsWith(".ttl") ) => "application/turtle"
        case _ if( source2.endsWith(".n3") ) => "application/n3"
        case _ => "application/text"
      }
      case _ => mimeType
    }
//    val res = Files.probeContentType(source)
    println( s"trySuffix  + $mimeType + $mimeType2" )
    mimeType2
  }
  
  private def guessFromHeader(mt:MediaType, url: String, t:SemanticURIType) : SemanticURIType = {
    val st = mt.subType 
    if(
        st == "rdf+xml" || 
        st == "turtle" ||
        st == "n3" ||
        st == "x-turtle" ) {
      SemanticURI
    } else t
  }

}