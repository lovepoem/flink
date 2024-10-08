/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.table.api.typeutils

import org.apache.flink.table.api.typeutils.ScalaCaseClassSerializerReflectionTest._

import org.assertj.core.api.Assertions.{assertThat, assertThatThrownBy}
import org.junit.jupiter.api.Test

/** Test obtaining the primary constructor of a case class via reflection. */
class ScalaCaseClassSerializerReflectionTest {

  @Test
  def usageExample(): Unit = {
    val constructor = ScalaCaseClassSerializer
      .lookupConstructor(classOf[SimpleCaseClass])

    val actual = constructor(Array("hi", 1.asInstanceOf[AnyRef]))

    assertThat(actual).isEqualTo(SimpleCaseClass("hi", 1))
  }

  @Test
  def genericCaseClass(): Unit = {
    val constructor = ScalaCaseClassSerializer
      .lookupConstructor(classOf[Generic[_]])

    val actual = constructor(Array(1.asInstanceOf[AnyRef]))

    assertThat(actual).isEqualTo(Generic[Int](1))
  }

  @Test
  def caseClassWithParameterizedList(): Unit = {
    val constructor = ScalaCaseClassSerializer
      .lookupConstructor(classOf[HigherKind])

    val actual = constructor(Array(List(1, 2, 3), "hey"))

    assertThat(actual).isEqualTo(HigherKind(List(1, 2, 3), "hey"))
  }

  @Test
  def tupleType(): Unit = {
    val constructor = ScalaCaseClassSerializer
      .lookupConstructor(classOf[(String, String, Int)])

    val actual = constructor(Array("a", "b", 7.asInstanceOf[AnyRef]))

    assertThat(actual).isEqualTo(("a", "b", 7))
  }

  @Test
  def unsupportedInstanceClass(): Unit = {

    val outerInstance = new OuterClass

    assertThatThrownBy(
      () =>
        ScalaCaseClassSerializer
          .lookupConstructor(classOf[outerInstance.InnerCaseClass]))
      .isInstanceOf(classOf[IllegalArgumentException])
  }

  @Test
  def valueClass(): Unit = {
    val constructor = ScalaCaseClassSerializer
      .lookupConstructor(classOf[Measurement])

    val arguments = Array(
      1.asInstanceOf[AnyRef],
      new DegreeCelsius(0.5f).asInstanceOf[AnyRef]
    )

    val actual = constructor(arguments)

    assertThat(actual).isEqualTo(Measurement(1, new DegreeCelsius(0.5f)))
  }
}

object ScalaCaseClassSerializerReflectionTest {

  case class SimpleCaseClass(name: String, var age: Int) {

    def this(name: String) = this(name, 0)

  }

  case class HigherKind(name: List[Int], id: String)

  case class Generic[T](item: T)

  class DegreeCelsius(val value: Float) extends AnyVal {
    override def toString: String = s"$value °C"
  }

  case class Measurement(i: Int, temperature: DegreeCelsius)

}

class OuterClass {

  case class InnerCaseClass(name: String, age: Int)

}
