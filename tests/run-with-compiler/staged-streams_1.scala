import dotty.tools.dotc.quoted.Toolbox._
import scala.quoted._

object Test {

  // TODO: remove as it exists in Quoted Lib
  sealed trait Var[T] {
    def get: Expr[T]
    def update(x: Expr[T]): Expr[Unit]
  }

  object Var {
    def apply[T: Type, U](init: Expr[T])(body: Var[T] => Expr[U]): Expr[U] = '{
      var x = ~init
      ~body(
        new Var[T] {
          def get: Expr[T] = '(x)
          def update(e: Expr[T]): Expr[Unit] = '{ x = ~e }
        }
      )
    }
  }

  type Id[A] = A

  trait Producer[A] { self =>
    type St
    val card: Cardinality

    def init(k: St => Expr[Unit]): Expr[Unit]
    def step(st: St, k: (A => Expr[Unit])): Expr[Unit]
    def hasNext(st: St): Expr[Boolean]
  }

  trait Cardinality
  case object AtMost1 extends Cardinality
  case object Many extends Cardinality

  trait StagedStream[A]
  case class Linear[A](producer: Producer[A]) extends StagedStream[A]
  case class Nested[A, B](producer: Producer[B], nestedf: B => StagedStream[A]) extends StagedStream[A]

  case class Stream[A](stream: StagedStream[Expr[A]]) {

     def fold[W: Type](z: Expr[W], f: ((Expr[W], Expr[A]) => Expr[W])): Expr[W] = {
       Var(z) { s: Var[W] => '{

           ~fold_raw[Expr[A]]((a: Expr[A]) => '{
               ~s.update(f(s.get, a))
           }, stream)

           ~s.get
         }
       }
     }

     private def fold_raw[A](consumer: A => Expr[Unit], s: StagedStream[A]): Expr[Unit] = {
       s match {
         case Linear(producer) => {
           producer.card match {
             case Many =>
               producer.init(sp => '{
                 while(~producer.hasNext(sp)) {
                   ~producer.step(sp, consumer)
                 }
               })
             case AtMost1 =>
               producer.init(sp => '{
                 if (~producer.hasNext(sp)) {
                   ~producer.step(sp, consumer)
                 }
               })
           }
         }
         case nested: Nested[a, bt] => {
            fold_raw[bt](((e: bt) => fold_raw(consumer, nested.nestedf(e))), Linear(nested.producer))
         }
       }
     }

    def map[B : Type](f: (Expr[A] => Expr[B])): Stream[B] = {
      Stream(mapRaw[Expr[A], Expr[B]](a => k => '{ ~k(f(a)) }, stream))
    }

    private def mapRaw[A, B](f: (A => (B => Expr[Unit]) => Expr[Unit]), s: StagedStream[A]): StagedStream[B] = {
      s match {
        case Linear(producer) => {
          val prod = new Producer[B] {

            type St = producer.St

            val card = producer.card

            def init(k: St => Expr[Unit]): Expr[Unit] = {
              producer.init(k)
            }

            def step(st: St, k: (B => Expr[Unit])): Expr[Unit] = {
              producer.step(st, el => f(el)(k))
            }

            def hasNext(st: St): Expr[Boolean] = {
              producer.hasNext(st)
            }
          }

          Linear(prod)
        }
        case nested: Nested[a, bt] => {
          Nested(nested.producer, (a: bt) => mapRaw[A, B](f, nested.nestedf(a)))
        }
      }
    }

    def flatMap[B : Type](f: (Expr[A] => Stream[B])): Stream[B] = {
      Stream(flatMapRaw[Expr[A], Expr[B]]((a => { val Stream (nested) = f(a); nested }), stream))
    }

    private def flatMapRaw[A, B](f: (A => StagedStream[B]), stream: StagedStream[A]): StagedStream[B] = {
      stream match {
        case Linear(producer) => Nested(producer, f)
        case nested: Nested[a, bt] =>
          Nested(nested.producer, (a: bt) => flatMapRaw[A, B](f, nested.nestedf(a)))
      }
    }

    def filter(f: (Expr[A] => Expr[Boolean])): Stream[A] = {
      val filterStream = (a: Expr[A]) =>
        new Producer[Expr[A]] {
          type St = Expr[A]
          val card = AtMost1

          def init(k: St => Expr[Unit]): Expr[Unit] =
            k(a)

          def step(st: St, k: (Expr[A] => Expr[Unit])): Expr[Unit] =
            k(st)

          def hasNext(st: St): Expr[Boolean] =
            f(st)
        }

      Stream(flatMapRaw[Expr[A], Expr[A]]((a => { Linear(filterStream(a)) }), stream))
    }

    // def moreTermination[A](f: Rep[Boolean] => Rep[Boolean], stream: StagedStream[A]): StagedStream[A] = {
    //   def addToProducer[A](f: Rep[Boolean] => Rep[Boolean], producer: Producer[A]): Producer[A] = {
    //     producer.card match {
    //         case Many =>
    //           new Producer[A] {
    //             type St = producer.St

    //             val card = producer.card
    //             def init(k: St => Rep[Unit]): Rep[Unit] =
    //               producer.init(k)
    //             def step(st: St, k: (A => Rep[Unit])): Rep[Unit] =
    //               producer.step(st, el => k(el))
    //             def hasNext(st: St): Rep[Boolean] =
    //               f(producer.hasNext(st))
    //           }
    //         case AtMost1 => producer
    //     }
    //   }
    //   stream match {
    //     case Linear(producer) => Linear(addToProducer(f, producer))
    //     case Nested(producer, nestedf) =>
    //       Nested(addToProducer(f, producer), (a: Id[_]) => moreTermination(f, nestedf(a)))
    //   }
    // }

    // private def addCounter[A](n: Expr[Int], producer: Producer[A]): Producer[(Expr[Int], A)] =
    //   new Producer[(Var[Int], A)] {
    //     type St = (Var[Int], producer.St)

    //     val card = producer.card
    //     def init(k: St => Rep[Unit]): Rep[Unit] = {
    //       producer.init(st => {
    //         var counter: Var[Int] = n
    //         k(counter, st)
    //       })
    //     }
    //     def step(st: St, k: (((Var[Int], A)) => Rep[Unit])): Rep[Unit] = {
    //         val (counter, nst) = st
    //         producer.step(nst, el => {
    //           k((counter, el))
    //         })
    //     }
    //     def hasNext(st: St): Rep[Boolean] = {
    //         val (counter, nst) = st
    //         producer.card match {
    //           case Many => counter > 0 && producer.hasNext(nst)
    //           case AtMost1 => producer.hasNext(nst)
    //         }
    //     }
    //   }

    // def takeRaw[A](n: Rep[Int], stream: StagedStream[A]): StagedStream[A] = {
    //   stream match {
    //     case Linear(producer) => {
    //       mapRaw[(Var[Int], A), A]((t => k => {
    //         t._1 = t._1 - 1
    //         k(t._2)
    //       }), Linear(addCounter(n, producer)))
    //     }
    //     case Nested(producer, nestedf) => {
    //       Nested(addCounter(n, producer), (t: (Var[Int], Id[_])) => {
    //         mapRaw[A, A]((el => k => {
    //           t._1 = t._1 - 1
    //           k(el)
    //         }), moreTermination(b => t._1 > 0 && b, nestedf(t._2)))
    //       })
    //     }
    //   }
    // }

    // def take(n: Rep[Int]): Stream[A] = Stream(takeRaw(n, stream))

  }

  object Stream {
    def of[A: Type](arr: Expr[Array[A]]): Stream[A] = {
      val prod = new Producer[Expr[A]] {
        type St = (Var[Int], Var[Int], Expr[Array[A]])

        val card = Many

        def init(k: St => Expr[Unit]): Expr[Unit] = {
          Var('{(~arr).length}) { n =>
            Var(0.toExpr){ i =>
              k((i, n, arr))
            }
          }
        }

        def step(st: St, k: (Expr[A] => Expr[Unit])): Expr[Unit] = {
          val (i, _, arr) = st
          '{
              val el = (~arr).apply(~i.get)
              ~i.update('{ ~i.get + 1 })
              ~k('(el))
          }
        }

        def hasNext(st: St): Expr[Boolean] =  {
          val (i, n, _) = st
          '{
              (~i.get < ~n.get)
          }
        }
      }

      Stream(Linear(prod))
    }
  }

  def test1() = Stream
    .of('{Array(1, 2, 3)})
    .fold('{0}, ((a: Expr[Int], b : Expr[Int]) => '{ ~a + ~b }))

  def test2() = Stream
    .of('{Array(1, 2, 3)})
    .map((a: Expr[Int]) => '{ ~a * 2 })
    .fold('{0}, ((a: Expr[Int], b : Expr[Int]) => '{ ~a + ~b }))

  def test3() = Stream
    .of('{Array(1, 2, 3)})
    .flatMap((d: Expr[Int]) => Stream.of('{Array(1, 2, 3)}).map((dp: Expr[Int]) => '{ ~d * ~dp }))
    .fold('{0}, ((a: Expr[Int], b : Expr[Int]) => '{ ~a + ~b }))

  def test4() = Stream
    .of('{Array(1, 2, 3)})
    .filter((d: Expr[Int]) => '{ ~d % 2 == 0 })
    .fold('{0}, ((a: Expr[Int], b : Expr[Int]) => '{ ~a + ~b }))

  def main(args: Array[String]): Unit = {
    println(test1().run)
    println
    println(test2().run)
    println
    println(test3().run)
    println
    println(test4().run)
  }
}



