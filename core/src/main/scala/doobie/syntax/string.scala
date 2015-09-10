package doobie.syntax

import doobie.util.atom._
import doobie.util.composite._
import doobie.util.query._
import doobie.util.update._
import doobie.syntax.process._

import doobie.hi._

import scalaz._, Scalaz._
import scalaz.stream.Process

import shapeless._

/** Module defining the `sql` string interpolator. */
object string {

  /** 
   * Typeclass for a flat vector of `Atom`s, analogous to `Composite` but with no nesting or
   * generalization to product types. Each element expands to some nonzero number of `?`
   * placeholders in the SQL literal, and the param vector itself has a `Composite` instance.
   */
  sealed trait Param[A] {
    val composite: Composite[A]
    val placeholders: List[Int]
  }

  /** 
   * Derivations for `Param`, which disallow embedding. Each interpolated query argument corresponds
   * with either an `Atom`, or with a singleton instance for a `NonEmptyList` of some atomic type,
   * derived with the `many` constructor.
   */
  object Param {

    /** Each `Atom` gives rise to a `Param`. */
    implicit def fromAtom[A](implicit ev: Atom[A]): Param[A] =
      new Param[A] {
        val composite = Composite.fromAtom(ev)
        val placeholders = List(1)
      }

    /** There is an empty `Param` for `HNil`. */
    implicit val ParamHNil: Param[HNil] =
      new Param[HNil] {
        val composite = Composite.typeClass.emptyProduct
        val placeholders = Nil
      }

    /** Inductively we can cons a new `Param` onto the head of a `Param` of an `HList`. */
    implicit def ParamHList[H, T <: HList](implicit ph: Param[H], pt: Param[T]) =
      new Param[H :: T] {
        val composite = Composite.typeClass.product[H,T](ph.composite, pt.composite)
        val placeholders = ph.placeholders ++ pt.placeholders
      }

    /** A `Param` for a *singleton* `Foldable1`, used exclusively to support `IN` clauses. */
    def many[F[_] <: AnyRef : Foldable1, A](t: F[A])(implicit ev: Atom[A]): Param[t.type] =
      new Param[t.type] {
        val composite = new Composite[t.type] {
          val length    = t.count
          val set       = (n: Int, in: t.type) => 
            t.foldLeft((n, ().point[PreparedStatementIO])) { case ((n, psio), a) =>
              (n + 1, psio *> ev.set(n, a))
            } ._2
          val meta      = List.fill(length)(ev.meta)
          val unsafeGet = (_: java.sql.ResultSet, _: Int) => fail
          val update    = (_: Int, _: t.type) => fail
          def fail      = sys.error("singleton `IN` composite does not support get or update")
        }
      val placeholders = List(t.count)
    }
  
  }

  /** 
   * String interpolator for SQL literals. An expression of the form `sql".. $a ... $b ..."` with
   * interpolated values of type `A` and `B` (which must have `[[Param]]` instances, derived 
   * automatically from `Meta` via `Atom`) yields a value of type `[[Builder]]``[(A, B)]`.
   */
  implicit class SqlInterpolator(private val sc: StringContext) {

    private val stackFrame = {
      import Predef._
      Thread.currentThread.getStackTrace.lift(3)
    }

    private def placeholders(n: Int): String =
      List.fill(n)("?").mkString(", ")

    /** 
     * Arity-abstracted method accepting a sequence of values along with `[[Param]]` 
     * witnesses, yielding a `[[Builder]]``[...]` parameterized over the product of the types of the 
     * passed arguments. This method uses the `ProductArgs` macro from Shapeless and has no
     * meaningful internal structure.
     */
    object sql extends ProductArgs {
      def applyProduct[A <: HList](a: A)(implicit ev: Param[A]) = {
        val sql = sc.parts.toList.fzipWith(ev.placeholders.map(placeholders) ++ List(""))(_ + _).suml
        new Builder(a, sql, stackFrame)(ev.composite)
      }
    }

  }

  /** 
   * Type computed by the `sql` interpolator, parameterized over the composite of the types of
   * interpolated arguments. This type captures the sql string and parameter types, which can
   * subsequently transformed into a `[[doobie.util.query.Query0 Query0]]` or 
   * `[[doobie.util.update.Update0 Update0]]` (see the associated methods).
   */
  final class Builder[A: Composite] private[string] (a: A, rawSql: String, stackFrame: Option[StackTraceElement]) {

    /** 
     * Construct a `[[doobie.util.query.Query0 Query0]]` from this `[[Builder]]`, parameterized over a
     * composite output type. 
     */
    def query[O: Composite]: Query0[O] =
      Query[A, O](rawSql, stackFrame).toQuery0(a)

    /** Construct an `[[doobie.util.update.Update0 Update0]]` from this `[[Builder]]`. */
    def update: Update0 =
      Update[A](rawSql, stackFrame).toUpdate0(a)

  }

}
