package rww.ldp.auth


import java.security.cert.X509Certificate
import scalaz.{ Success => _, Failure => _, Validation =>_ , _ }
import scalaz.Scalaz._
import java.security.{Principal, PublicKey}
import java.net.{MalformedURLException, URL, URISyntaxException}
import rww.ldp.actor.RWWActorSystem
import rww.ldp.LDPCommand
import java.math.BigInteger
import java.security.interfaces.RSAPublicKey
import scalaz.Semigroup._
import scala.concurrent.{ExecutionContext, Future}
import util.{Failure, Success, Try}
import org.w3.banana._
import org.slf4j.LoggerFactory
import java.lang.Exception
import scala.Exception

object WebIDVerifier {

  /*
  * Useful when converting the bytes from a BigInteger to a hex for inclusion in rdf
  **/
  def hex(bytes: Array[Byte]): String = bytes.dropWhile(_ == 0).map("%02X" format _).mkString

  def stripSpace(hex: String): String = hex.filter(c => !Character.isWhitespace(c))

}


/**
 *
 */
class WebIDVerifier[Rdf <: RDF](rww: RWWActorSystem[Rdf])
                               (implicit ops: RDFOps[Rdf],
                                sparqlOps: SparqlOps[Rdf],
                                val ec: ExecutionContext)   {
  import sparqlOps._
  import ops._
  import WebIDVerifier._

  val logger = LoggerFactory.getLogger(this.getClass)

  //todo: find a good sounding name for (String,PublicKey)
  //todo document what is going on eg: sanPair.get(1)

  def verify(x509claim: Claim[X509Certificate]): List[Future[Principal]] = {
    val listOfClaims =  webidClaims(x509claim).sequence
    for ( webidclaim <- listOfClaims) yield verifyWebIDClaim(webidclaim)
  }

  /**
   * transform a X509Cert Claim to a Claims about (san,publickyes) that can the be verified
   * @param x509claim
   * @return Claim of list of sans
   */
  protected def webidClaims(x509claim: Claim[X509Certificate]): Claim[List[(String, PublicKey)]] =
    for (x509 <- x509claim) yield {
      Option(x509.getSubjectAlternativeNames).toList.flatMap { collOfNames =>
        import scala.collection.JavaConverters.iterableAsScalaIterableConverter
        for {
          sanPair <- collOfNames.asScala;
          if (sanPair.get(0) == 6)
        } yield (sanPair.get(1).asInstanceOf[String].trim,x509.getPublicKey)
      }
    }





  val base10Types = List(xsd("integer"),xsd("int"),xsd("positiveInteger"),xsd("decimal"))

  //  val webidVerifier = {
  //    val wiv = context.actorFor("webidVerifier")
  //    if (wiv == context.actorFor("/deadLetters"))
  //      context.actorOf(Props(classOf[WebIDClaimVerifier]),"webidVerifier")
  //    else wiv
  //  }

  val query = parseSelect("""
      PREFIX : <http://www.w3.org/ns/auth/cert#>
      SELECT ?m ?e
      WHERE {
          ?webid :key [ :modulus ?m ;
                        :exponent ?e ].
      }""").get

  /**
   * transform an RDF#Node to a positive Integer if possible
   * A bit heavy this implementation! Can't use asInstanceOf[T] as that info is sadly erased
   * @param node the node - as a literal - that should be the positive integer
   * @return a Validation containing and exception or the number
   */
  private def toPositiveInteger(node: Rdf#Node): Try[BigInteger] =
    foldNode(node)(
      _=> Failure(FailedConversion("node must be a typed literal; it was: "+node)),
      _=> Failure(FailedConversion("node must be a typed literal; it was: "+node)),
      lit => try {
        fromLiteral(lit) match {
          case (hexStr, xsd("hexBinary"), None) => Success(new BigInteger(stripSpace(hexStr), 16))
          case (base10Str, base10Tp, None) if base10Types.contains(base10Tp) =>
            Success(new BigInteger(base10Str))
          case (_, tp, None) => Failure(
            FailedConversion("do not recognise datatype " + tp + " as one of the legal numeric ones in node: " + node)
          )
          case _ => Failure(FailedConversion("require a TypedLiteral not a LangLiteral. Received " + node))
        }
      } catch {
        case num: NumberFormatException =>
          Failure(FailedConversion("failed to convert to integer "+node+" - "+num.getMessage))
      }
    )


  /**
   * function to verifyWebIDClaim that a given Subject Alternative Name referent is the owner of a public key
   * @param san
   * @param key
   * @return a Future Try of the WebIDPrincipal. The Try is not merged into the future
   */
  def verifyWebID(san: String, key: PublicKey):  Future[Principal] =  try {
    logger.debug(s"in verifyWebID for san=$san")
    import LDPCommand._
    val uri = new java.net.URI(san)
    val ir = san.split("#")(0)
    val webidProfile = new java.net.URI(ir)
    val scheme = webidProfile.getScheme
    if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
      Future.failed(
        UnsupportedProtocol("we only support http and https urls at present - this one was "+scheme, san)
      )
    else key match {
      case rsaKey: RSAPublicKey =>  {
        val wid = URI(san)
        rww.execute(selectLDPR(URI(ir),query,Map("webid" -> wid))).flatMap { sols=>
          val s: Iterator[Try[WebIDPrincipal]] = solutionIterator(sols).map { sol: Rdf#Solution =>

            val keyVal = for { mod <- getNode(sol,"m") flatMap { toPositiveInteger(_) }
                               exp <- getNode(sol,"e") flatMap { toPositiveInteger(_) }
            } yield RSAPubKey(mod,exp)
            logger.debug(s"solutions for $ir=$keyVal")

            keyVal.flatMap { key =>
              if (key.modulus == rsaKey.getModulus && key.exponent == rsaKey.getPublicExponent) Success(WebIDPrincipal(uri))
              else Failure(new KeyMatchFailure(s"RSA key does not match one in profile. $san $rsaKey $key",san,rsaKey,key))
            }
          }
          val result: Try[WebIDPrincipal] = s.find(_.isSuccess).getOrElse {
            val failures: List[BananaException] = s.toList.map(  t =>
              t match {
                case Failure(e: BananaException) => e
                case Failure(e)  => WrappedThrowable(e) // a RunTimeException?
                case Success(e)  => throw new Error("should never reach this point")
              }
            )
            Failure(
              if (failures.size == 0) WebIDVerificationFailure(s"no rsa keys found in profile for WebID. $uri",uri)
              else WebIDVerificationFailure(s"no keys matched the WebID in the profile $uri",uri,failures)
            )
          }
          if ( result.isSuccess ) {
            logger.info(s"Successfully authenticated as ${result.get}")
          }
          result.asFuture
        }
      }
      case _ => Future.failed(
        new UnsupportedKeyType("cannot verifyWebIDClaim WebID <"+san+"> with key of type "+key.getAlgorithm,key)
      )
    }
  } catch {
    case e: URISyntaxException  =>   Future.failed(URISyntaxError(s"could not parse uri $san",List(e),san))
    case e: MalformedURLException => Future.failed(URISyntaxError(s"could not parse SAN as a URL $san",List(e),san))
    case e: Exception => Future.failed(WrappedThrowable(e))
  }


  def verifyWebIDClaim(webidClaim: Claim[Pair[String,PublicKey]]): Future[Principal] =
    webidClaim.verify { sk => verifyWebID(sk._1,sk._2) }
}


/**
 * A Claim is a Monad that contains something akin to a set of statements, that are not known to
 * be either true or false. The statement can only be extracted via a verifyWebIDClaim method
 *
 * @tparam S A object that represents a set of statement of some form. It can be an object that has relations
 *           to other objects which together can be thought of as a set of statements. Or it could be for example
 *           an RDF graph.
 */
trait Claim[+S] {
  protected val statements: S

  //warning: implicit here is not a good idea at all
  def verify[V](implicit fn: S=> V ): V
}

object Claim {
  implicit val ClaimMonad: Monad[Claim] with Traverse[Claim] =
    new Monad[Claim] with Traverse[Claim] {

      def traverseImpl[G[_] : Applicative, A, B](fa: Claim[A])(f: A => G[B]): G[Claim[B]] =
        f(fa.statements).map(a => this.point(a))

      def point[A](a: => A) = new Claim[A]{
        protected val statements : A = a;
        def verify[V](implicit fn: A=> V ) = fn(statements)
      }

      def bind[A, B](fa: Claim[A])(f: (A) => Claim[B]) = f(fa.statements)

    }

}

case class WebIDPrincipal(webid: java.net.URI) extends Principal {
  val getName = webid.toString
}

case class RSAPubKey(modulus: BigInteger, exponent: BigInteger)


object VerificationException {
  implicit val bananaExceptionSemiGroup = firstSemigroup[VerificationException]

}

trait VerificationException extends BananaException {
  //  type T <: AnyRef
  //  val msg: String
  //  val cause: List[Throwable]=Nil
  //  val findSubject: T
}

// TODO these exceptions should rather be refactored

trait WebIDClaimFailure extends VerificationException

class UnsupportedKeyType(val msg: String, val subject: PublicKey) extends Exception(msg) with WebIDClaimFailure { type T = PublicKey }

case class WebIDVerificationFailure(msg: String, webid: java.net.URI, failures: List[BananaException] = Nil) extends Exception(msg) with WebIDClaimFailure {
  failures.foreach( this.addSuppressed(_))
}

trait SANFailure extends WebIDClaimFailure { type T = String }
case class UnsupportedProtocol(val msg: String, subject: String) extends Exception(msg) with SANFailure
case class URISyntaxError(val msg: String, val cause: List[Throwable], subject: String) extends Exception(msg) with SANFailure {
  cause.foreach( this.addSuppressed(_))
}

//The findSubject could be more refined than the URL, especially in the paring error
trait ProfileError extends WebIDClaimFailure { type T = URL }
case class ProfileGetError(val msg: String,  val cause: List[Throwable], subject: URL) extends Exception(msg) with ProfileError {
  cause.foreach( this.addSuppressed(_))
}
case class ProfileParseError(val msg: String, val cause: List[Throwable], subject: URL) extends Exception(msg) with ProfileError {
  cause.foreach( this.addSuppressed(_))
}



//it would be useful to pass the graph in
//perhaps change the WebID to the doc uri where it was fetched finally.
case class KeyMatchFailure(val msg: String, webid: String, certKey: RSAPublicKey, comparedWith: RSAPubKey ) extends Exception(msg) with VerificationException