package com.github.aselab.activerecord.inner

import com.github.aselab.activerecord._
import com.github.aselab.activerecord.dsl._
import com.github.aselab.activerecord.aliases._
import com.github.aselab.activerecord.squeryl.Implicits._
import ActiveRecord._
import reflections._
import ReflectionUtil._

trait Relations {
  case class Parameters[T <: AR, JoinedType <: {def _1: T}, S](
    conditions: List[JoinedType => LogicalBoolean] = Nil,
    orders: List[JoinedType => ExpressionNode] = Nil,
    selector: JoinedType => S = {t: JoinedType => t._1},
    includeAssociations: List[T => Association[T, AR]] = Nil,
    pages: Option[(Int, Int)] = None,
    isUnique: Boolean = false,
    isReverse: Boolean = false
  )

  trait QuerySupport[T <: AR, S] {
    type TupleN = {def _1: T}
    type JoinedType <: TupleN
    type Inc[A <: AR] = T => Association[T, A]

    val parameters: Parameters[T, JoinedType, S]

    val manifest: Manifest[T]

    lazy val companion: ActiveRecordBaseCompanion[_, T] =
      classToARCompanion(manifest.erasure)

    // scalastyle:off
    def conditions = parameters.conditions
    def orders = parameters.orders
    def includeAssociations = parameters.includeAssociations
    def pages = parameters.pages
    def isUnique = parameters.isUnique
    def isReverse = parameters.isReverse
    def selector = parameters.selector
    // scalastyle:on

    protected def copyParams[R](params: Parameters[T, JoinedType, R]): Relation[T, R]

    protected def copyParams[R](
      conditions: List[JoinedType => LogicalBoolean] = parameters.conditions,
      orders: List[JoinedType => ExpressionNode] = parameters.orders,
      selector: JoinedType => R = parameters.selector,
      includeAssociations: List[T => Association[T, AR]] = parameters.includeAssociations,
      pages: Option[(Int, Int)] = parameters.pages,
      isUnique: Boolean = parameters.isUnique,
      isReverse: Boolean = parameters.isReverse
    ): Relation[T, R] = copyParams(
      Parameters(conditions, orders, selector, includeAssociations, pages, isUnique, isReverse)
    )

    protected implicit def relationToThisType[R](self: Relation[T, R]): this.type =
      self.asInstanceOf[this.type]

    private def _includes(associations: Inc[_]*): this.type =
      copyParams(includeAssociations =
        includeAssociations ++ associations.map(_.asInstanceOf[Inc[AR]]))

    def includes[A <: AR](association: Inc[A]): this.type =
      _includes(association)

    def includes[A1 <: AR, A2 <: AR](a1: Inc[A1], a2: Inc[A2]): this.type =
      _includes(a1, a2)

    def includes[A1 <: AR, A2 <: AR, A3 <: AR](
      a1: Inc[A1], a2: Inc[A2], a3: Inc[A3]
    ): this.type = _includes(a1, a2, a3)

    def includes[A1 <: AR, A2 <: AR, A3 <: AR, A4 <: AR](
      a1: Inc[A1], a2: Inc[A2], a3: Inc[A3], a4: Inc[A4]
    ): this.type = _includes(a1, a2, a3, a4)

    protected def whereScope(m: JoinedType) = if (conditions.isEmpty) {
      dsl
    } else {
      dsl.where(LogicalBoolean.and(conditions.map(_.apply(m))))
    }

    protected def ordersExpression(m: JoinedType) =
      if (!isReverse) orders.map(_.apply(m)) else reverseOrder(m)

    protected def paginate[R](query: Query[R]) = {
      val q = if (isUnique) query.distinct else query
      pages.map {
        case (offset, count) => q.page(offset, count)
      }.getOrElse(q)
    }

    private def toOrderByExpression(e: ExpressionNode) =
      new OrderByExpression(new OrderByArg(e))

    protected def reverseOrder(m: JoinedType): List[OrderByExpression] =
      if (orders.isEmpty) {
        List(new OrderByArg(companion.fieldInfo("id").toExpression(m._1.id)).desc)
      } else {
        orders.map(_.apply(m)).map{
          case o: OrderByExpression => {
            val orderByArg = o.getValue[OrderByArg]("a")
            val isAsc = orderByArg.getValue[Boolean]("isAscending")
            o.setValue("a", if (isAsc) orderByArg.desc else orderByArg.asc)
            o
          }
          case e: ExpressionNode => toOrderByExpression(e).inverse
        }
      }

    def reverse: this.type = copyParams(isReverse = !isReverse)

    protected def wrap[A <: TupleN, R](f: T => R): A => R = {m: A => f(m._1)}

    protected def wrapTuple1[A <: TupleN, R](f: Tuple1[T] => R): A => R =
      {t: A => f(Tuple1(t._1))}

    def where(condition: T => LogicalBoolean): this.type =
      copyParams(conditions = conditions :+ wrap(condition))

    def not(condition: T => LogicalBoolean): this.type =
      copyParams(conditions = conditions :+ wrap(condition.andThen(dsl.not)))

    def orderBy(conditions: (T => ExpressionNode)*): this.type =
      copyParams(orders = orders ++ conditions.map(wrap))

    def select[R](selector: T => R): Relation[T, R] =
      copyParams(selector = wrap(selector))

    /**
     * returns limited results.
     * {{{
     * Post.all.orderBy(p => p.updatedAt desc).limit(10)
     * }}}
     * @param count max count
     */
    def limit(count: Int): this.type =
      page(pages.map(_._1).getOrElse(0), count)

    def distinct: this.type =
      copyParams(isUnique = true)

    /**
     * returns page results.
     * {{{
     * Post.all.orderBy(p => p.updatedAt desc).page(10 * (pageNumber - 1), 10)
     * }}}
     * @param offset offset count
     * @param count max count
     */
    def page(offset: Int, count: Int): this.type =
      copyParams(pages = Some(offset, count))
  }

  trait Relation[T <: AR, S] extends QuerySupport[T, S] {
    val queryable: Queryable[T]

    implicit protected def convertFactory[A](f: TypedExpressionFactory[A, _]) =
      f.asInstanceOf[TypedExpressionFactory[A, Any]]

    private var _isLoaded = false
    def isLoaded: Boolean = _isLoaded

    private var _cache: List[S] = Nil
    private[inner] def cache = _cache
    private[inner] def cache_=(value: List[S]) = {
      _cache = value
      _isLoaded = true
      value
    }

    def reload(implicit m: Manifest[S]): List[S] = companion.inTransaction {
      cache = queryToIterable(toQuery).toList

      if (manifest == m && cache.nonEmpty) {
        val sources = cache.asInstanceOf[List[T]]
        val sample = companion.newInstance
        val eagerLoadedMaps = includeAssociations.map(a =>
          (a, a(sample).eagerLoad(sources)(manifest))
        )
        for ((associationOf, map) <- eagerLoadedMaps; m <- sources) {
          associationOf(m).relation.cache = map.getOrElse(m.id, Nil)
        }
      }
      cache
    }

    def load(implicit m: Manifest[S]): List[S] = if (isLoaded) cache else reload

    private def getOrException(o: Option[S]) = try {
      o.get
    } catch { case e: java.util.NoSuchElementException =>
      throw ActiveRecordException.recordNotFound
    }

    def head: S = getOrException(headOption)

    def headOption: Option[S] = if (isLoaded) {
      cache.headOption
    } else {
      companion.inTransaction { limit(1).toQuery.headOption }
    }

    def last: S = getOrException(lastOption)

    def lastOption: Option[S] = reverse.limit(1).toQuery.headOption

    /**
     * Search by multiple fieldnames and values and return first record.
     *
     * {{{
     * findBy("name" -> "abc", "email" -> "abc@foo.bar")
     * }}}
     * @param condition fieldname-value tuple
     * @param conditions multiple fieldname-value tuples(optional)
     */
    def findBy(condition: (String, Any), conditions: (String, Any)*): Option[S] = companion.inTransaction {
      findAllBy(condition, conditions:_*).headOption
    }

    def findByOrCreate(m: T, field: String, fields: String*)(implicit ev: T =:= S): S = {
      findBy((field, m.getValue(field)),
        fields.map(f => (f, m.getValue(f))).toSeq:_*).getOrElse(m.create)
    }

    /**
     * Search by multiple fieldnames and values.
     *
     * {{{
     * findAllBy("name" -> "abc", "email" -> "abc@foo.bar")
     * }}}
     * @param condition fieldname-value tuple
     * @param conditions multiple fieldname-value tuples(optional)
     */
    def findAllBy(condition: (String, Any), conditions: (String, Any)*): this.type =
      conditions.foldLeft(findAllBy(condition._1, condition._2)) {
        case (r, cond) => r.findAllBy(cond._1, cond._2)
      }

    /**
     * Search by fieldname and value and return first record.
     * {{{
     * findBy("name", "abc")
     * }}}
     * @param name field name
     * @param value field value
     */
    def findBy(name: String, value: Any): Option[S] = companion.inTransaction {
      findAllBy(name, value).headOption
    }

    /**
     * Search by fieldname and value.
     * {{{
     * findAllBy("name", "abc")
     * }}}
     * @param name field name
     * @param value field value
     */
    def findAllBy(name: String, value: Any): this.type = {
      val field = companion.fieldInfo.getOrElse(name,
        throw ActiveRecordException.notFoundField(name)
      )
      where(m => field.toEqualityExpression(m.getValue[Any](name), value))
    }

    def deleteAll()(implicit ev: S =:= T): List[T] = companion.inTransaction {
      val records = toQuery.toList
      records.foreach(_.delete)
      records.asInstanceOf[List[T]]
    }

    def exists(condition: T => LogicalBoolean): Boolean = companion.inTransaction {
      where(condition).limit(1).count != 0
    }

    def count: Long = if (isUnique) {
      toQuery.Count
    } else {
      toQuery(t => whereScope(t).compute(dsl.count))
    }

    def compute[T1](e: T => TypedExpression[T1, _]): T1 =
      toQuery(t => whereScope(t).compute(e(t._1)))

    def maximum[T2 >: TOption, T1 <: T2, A1, A2](e: T => TypedExpression[A1, T1])
      (implicit f: TypedExpressionFactory[A2, T2]): A2 =
        compute(m => dsl.max(e(m))(f))

    def minimum[T2 >: TOption, T1 <: T2, A1, A2](e: T => TypedExpression[A1, T1])
      (implicit f: TypedExpressionFactory[A2, T2]): A2 =
        compute(m => dsl.min(e(m))(f))

    def average[T2 >: TOptionFloat, T1 <: T2, A1, A2]
      (e: T => TypedExpression[A1, T1])
      (implicit f: TypedExpressionFactory[A2, T2]): A2 =
        compute(m => dsl.avg(e(m))(f))

    def max[T2 >: TOption, T1 <: T2, A1, A2](e: T => TypedExpression[A1, T1])
      (implicit f: TypedExpressionFactory[A2, T2]): A2 = maximum(e)(f)

    def min[T2 >: TOption, T1 <: T2, A1, A2](e: T => TypedExpression[A1, T1])
      (implicit f: TypedExpressionFactory[A2, T2]): A2 = minimum(e)(f)

    def avg[T2 >: TOptionFloat, T1 <: T2, A1, A2]
      (e: T => TypedExpression[A1, T1])
      (implicit f: TypedExpressionFactory[A2, T2]): A2 = average(e)(f)

    def sum[T2 >: TOption, T1 >: TNumericLowerTypeBound <: T2, A1, A2]
      (e: T => TypedExpression[A1, T1])
      (implicit f: TypedExpressionFactory[A2, T2]): A2 =
        compute(m => dsl.sum(e(m))(f))

    protected def toQuery[R](f: JoinedType => QueryYield[R]): Query[R]

    def toQuery: Query[S] = paginate(toQuery(t =>
      whereScope(t).select(selector(t)).orderBy(ordersExpression(t))
    ))

    def toSql: String = companion.inTransaction { toQuery.statement }
  }

  case class Relation1[T <: AR, S](
    parameters: Parameters[T, Tuple1[T], S],
    queryable: Queryable[T]
  )(implicit val manifest: Manifest[T]) extends Relation[T, S] {
    type JoinedType = Tuple1[T]

    protected def copyParams[R](params: Parameters[T, JoinedType, R]) =
      Relation1(params, queryable)

    protected def toQuery[R](f: JoinedType => QueryYield[R]): Query[R] =
      from(queryable)(m => f(Tuple1(m)))

    def joins[J <: AR](on: (T, J) => LogicalBoolean)
      (implicit m: Manifest[J]): Relation2[T, J, S] = {
      val c = classToARCompanion[J](m.erasure)

      Relation2(
        Parameters[T, (T, J), S](conditions.map(wrapTuple1), orders.map(wrapTuple1),
          wrapTuple1[(T, J), S](selector), includeAssociations, pages, isUnique),
        queryable, c.table, on.tupled
      )(manifest)
    }

    def joins[J1 <: AR, J2 <: AR](
      on: (T, J1, J2) => (LogicalBoolean, LogicalBoolean)
    )(implicit m1: Manifest[J1], m2: Manifest[J2]): Relation3[T, J1, J2, S] = {
      val c1 = classToARCompanion[J1](m1.erasure)
      val c2 = classToARCompanion[J2](m2.erasure)

      Relation3(
        Parameters[T, (T, J1, J2), S](conditions.map(wrapTuple1), orders.map(wrapTuple1),
          wrapTuple1[(T, J1, J2), S](selector), includeAssociations, pages, isUnique),
        queryable, c1.table, c2.table, on.tupled
      )(manifest)
    }

    def joins[J1 <: AR, J2 <: AR, J3 <: AR](
      on: (T, J1, J2, J3) => (LogicalBoolean, LogicalBoolean, LogicalBoolean)
    )(implicit m1: Manifest[J1], m2: Manifest[J2], m3: Manifest[J3]): Relation4[T, J1, J2, J3, S] = {
      val c1 = classToARCompanion[J1](m1.erasure)
      val c2 = classToARCompanion[J2](m2.erasure)
      val c3 = classToARCompanion[J3](m3.erasure)

      Relation4(
        Parameters[T, (T, J1, J2, J3), S](conditions.map(wrapTuple1), orders.map(wrapTuple1),
          wrapTuple1[(T, J1, J2, J3), S](selector), includeAssociations, pages, isUnique),
        queryable, c1.table, c2.table, c3.table, on.tupled
      )(manifest)
    }
  }

  case class Relation2[T <: AR, J1 <: AR, S](
    parameters: Parameters[T, (T, J1), S],
    queryable: Queryable[T],
    joinTable: Queryable[J1],
    on: ((T, J1)) => LogicalBoolean
  )(implicit val manifest: Manifest[T]) extends Relation[T, S] {
    type JoinedType = (T, J1)

    protected def copyParams[R](params: Parameters[T, JoinedType, R]) =
      Relation2(params, queryable, joinTable, on)

    def where(condition: (T, J1) => LogicalBoolean): this.type =
      copyParams(conditions = conditions :+ condition.tupled)

    def select[R](selector: (T, J1) => R): Relation[T, R] =
      copyParams(selector = selector.tupled)

    def orderBy(conditions: ((T, J1) => ExpressionNode)*): this.type =
      copyParams(orders = orders ++ conditions.toList.map(_.tupled))

    def compute[T1](e: (T, J1) => TypedExpression[T1, _]): T1 =
      toQuery(t => whereScope(t).compute(e.tupled(t)))

    protected def toQuery[R](f: JoinedType => QueryYield[R]): Query[R] =
      join(queryable, joinTable) {(m, j1) =>
        val t = (m, j1)
        f(t).on(on(t))
      }
  }

  case class Relation3[T <: AR, J1 <: AR, J2 <: AR, S](
    parameters: Parameters[T, (T, J1, J2), S],
    queryable: Queryable[T],
    joinTable1: Queryable[J1],
    joinTable2: Queryable[J2],
    on: ((T, J1, J2)) => (LogicalBoolean, LogicalBoolean)
  )(implicit val manifest: Manifest[T]) extends Relation[T, S] {
    type JoinedType = (T, J1, J2)

    protected def copyParams[R](params: Parameters[T, JoinedType, R]) =
      Relation3(params, queryable, joinTable1, joinTable2, on)

    def where(condition: (T, J1, J2) => LogicalBoolean): this.type =
      copyParams(conditions = conditions :+ condition.tupled)

    def select[R](selector: (T, J1, J2) => R): Relation[T, R] =
      copyParams(selector = selector.tupled)

    def orderBy(conditions: ((T, J1, J2) => ExpressionNode)*): this.type =
      copyParams(orders = orders ++ conditions.toList.map(_.tupled))

    def compute[T1](e: (T, J1, J2) => TypedExpression[T1, _]): T1 =
      toQuery(t => whereScope(t).compute(e.tupled(t)))

    protected def toQuery[R](f: JoinedType => QueryYield[R]): Query[R] =
      join(queryable, joinTable1, joinTable2) {(m, j1, j2) =>
        val t = (m, j1, j2)
        val (on1, on2) = on(t)
        f(t).on(on1, on2)
      }
  }

  case class Relation4[T <: AR, J1 <: AR, J2 <: AR, J3 <: AR, S](
    parameters: Parameters[T, (T, J1, J2, J3), S],
    queryable: Queryable[T],
    joinTable1: Queryable[J1],
    joinTable2: Queryable[J2],
    joinTable3: Queryable[J3],
    on: ((T, J1, J2, J3)) => (LogicalBoolean, LogicalBoolean, LogicalBoolean)
  )(implicit val manifest: Manifest[T]) extends Relation[T, S] {
    type JoinedType = (T, J1, J2, J3)

    protected def copyParams[R](params: Parameters[T, JoinedType, R]) =
      Relation4(params, queryable, joinTable1, joinTable2, joinTable3, on)

    def where(condition: (T, J1, J2, J3) => LogicalBoolean): this.type =
      copyParams(conditions = conditions :+ condition.tupled)

    def select[R](selector: (T, J1, J2, J3) => R): Relation[T, R] =
      copyParams(selector = selector.tupled)

    def orderBy(conditions: ((T, J1, J2, J3) => ExpressionNode)*): this.type =
      copyParams(orders = orders ++ conditions.toList.map(_.tupled))

    def compute[T1](e: (T, J1, J2, J3) => TypedExpression[T1, _]): T1 =
      toQuery(t => whereScope(t).compute(e.tupled(t)))

    protected def toQuery[R](f: JoinedType => QueryYield[R]): Query[R] =
      join(queryable, joinTable1, joinTable2, joinTable3) {(m, j1, j2, j3) =>
        val t = (m, j1, j2, j3)
        val (on1, on2, on3) = on(t)
        f(t).on(on1, on2, on3)
      }
  }
}
