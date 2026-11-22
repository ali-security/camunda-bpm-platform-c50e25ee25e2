/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.camunda.bpm.engine.impl.query;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.fail;

import org.camunda.bpm.engine.BadUserRequestException;
import org.camunda.bpm.engine.ProcessEngineConfiguration;
import org.camunda.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.junit.Before;
import org.junit.Test;

public class DefaultQueryExpressionValidatorTest {

  protected ProcessEngineConfiguration configuration;
  protected DefaultQueryExpressionValidator validator;

  @Before
  public void setUp() {
    configuration = new StandaloneInMemProcessEngineConfiguration();
    validator = new DefaultQueryExpressionValidator(configuration);
  }

  @Test
  public void shouldAcceptNullAndPlainText() {
    assertAllowed(null);
    assertAllowed("");
    assertAllowed("plain text");
    assertAllowed("camunda-admin, accounting");
  }

  @Test
  public void shouldAcceptLiteralsAndOperators() {
    assertAllowed("${'text'}");
    assertAllowed("${42}");
    assertAllowed("${true}");
    assertAllowed("${null}");
    assertAllowed("${1 + 2}");
    assertAllowed("${'a' == 'b' ? 'c' : 'd'}");
    assertAllowed("${empty 'text'}");
    assertAllowed("prefix ${'text'} suffix");
  }

  @Test
  public void shouldAcceptDefaultFunctions() {
    assertAllowed("${now()}");
    assertAllowed("${ now() }");
    assertAllowed("${dateTime()}");
    assertAllowed("${currentUser()}");
    assertAllowed("${currentUserGroups()}");
  }

  @Test
  public void shouldAcceptDefaultMethods() {
    assertAllowed("${dateTime().plusDays(2)}");
    assertAllowed("${dateTime().minusDays(2)}");
    assertAllowed("${dateTime().plusWeeks(2)}");
    assertAllowed("${dateTime().minusSeconds(10)}");
    assertAllowed("${dateTime().withMillis(0)}");
    assertAllowed("${dateTime().withTimeAtStartOfDay()}");
    assertAllowed("${dateTime().plusDays(4).withTimeAtStartOfDay()}");
    assertAllowed("${dateTime().withTimeAtStartOfDay().plusDays(1).minusSeconds(1).toDate()}");
  }

  @Test
  public void shouldAcceptEveryDocumentedDefaultMethod() {
    // the Joda-Time DateTime methods listed as built in by Camunda's patch-level update guide
    // (query expression allow-list, 7.23.12 / 7.22.15)
    String[] methods = {
        "plusYears", "plusMonths", "plusWeeks", "plusDays", "plusHours", "plusMinutes", "plusSeconds", "plusMillis",
        "minusYears", "minusMonths", "minusWeeks", "minusDays", "minusHours", "minusMinutes", "minusSeconds", "minusMillis",
        "withYear", "withMonthOfYear", "withDayOfMonth", "withDayOfWeek", "withDayOfYear", "withHourOfDay",
        "withMinuteOfHour", "withSecondOfMinute", "withMillisOfSecond", "withMillis"};

    for (String method : methods) {
      assertAllowed("${dateTime()." + method + "(1)}");
    }
    assertAllowed("${dateTime().withTimeAtStartOfDay()}");
    assertAllowed("${dateTime().toDate()}");
  }

  @Test
  public void shouldAcceptBracketIndexing() {
    assertAllowed("${currentUserGroups()[0]}");
    assertAllowed("${currentUserGroups()[1 + 1]}");
  }

  @Test
  public void shouldAcceptDeferredSyntaxWithAllowedElements() {
    assertAllowed("#{now()}");
  }

  @Test
  public void shouldRejectUnknownFunction() {
    assertRejected("${unknownFunction()}", "function 'unknownFunction'");
    assertRejected("${ns:now()}", "function 'ns:now'");
  }

  @Test
  public void shouldRejectBeanReference() {
    assertRejected("${someBean}", "bean 'someBean'");
    assertRejected("${ someBean }", "bean 'someBean'");
    assertRejected("#{someBean}", "bean 'someBean'");
  }

  @Test
  public void shouldRejectBeanReferenceInOperandsAndParameters() {
    assertRejected("${1 + someBean}", "bean 'someBean'");
    assertRejected("${true ? someBean : 'a'}", "bean 'someBean'");
    assertRejected("${dateTime().plusDays(someBean)}", "bean 'someBean'");
    assertRejected("${currentUserGroups()[someBean]}", "bean 'someBean'");
  }

  @Test
  public void shouldRejectPropertyAccess() {
    assertRejected("${currentUserGroups().size}", "property 'size'");
    assertRejected("${dateTime().millis}", "property 'millis'");
    assertRejected("${''.class}", "property 'class'");
  }

  @Test
  public void shouldRejectAllowedMethodNameUsedAsProperty() {
    assertRejected("${dateTime().plusDays}", "property 'plusDays'");
  }

  @Test
  public void shouldRejectUnknownMethod() {
    assertRejected("${currentUser().length()}", "method 'length'");
    assertRejected("${now().getTime()}", "method 'getTime'");
  }

  @Test
  public void shouldRejectReflectionChain() {
    assertRejected("${''.getClass().forName('java.lang.Runtime')}", "method 'forName'");
    assertRejected("${''.getClass().forName('java.lang.Runtime').getMethod('getRuntime').invoke(null).exec('id')}",
        "method 'exec'");
    assertRejected("${dateTime().getClass()}", "method 'getClass'");
  }

  @Test
  public void shouldRejectReflectionChainNestedInParameter() {
    assertRejected("${dateTime().plusDays(''.getClass().getName().length())}", "method 'length'");
  }

  @Test
  public void shouldRejectMethodInvocationInBracketNotation() {
    assertRejected("${now()['getTime']()}", "bracket notation");
    assertRejected("${dateTime()['plusDays'](1)}", "bracket notation");
  }

  @Test
  public void shouldRejectUnparsableExpression() {
    assertRejected("${1 +}", "cannot be parsed");
    assertRejected("${now(}", "cannot be parsed");
  }

  @Test
  public void shouldIncludeExpressionAndConfigurationPropertyInMessage() {
    assertThatThrownBy(() -> validator.validateExpression("${someBean}"))
        .isInstanceOf(BadUserRequestException.class)
        .hasMessageContaining("Expression '${someBean}' is not allowed in query criteria")
        .hasMessageContaining("allowedExpressionBeansInQueries");
  }

  @Test
  public void shouldAcceptConfiguredFunctionsAndMethods() {
    // given
    configuration.setAllowedExpressionFunctionsInQueries("myFunction, concat,,");

    // then
    assertAllowed("${myFunction()}");
    assertAllowed("${'%'.concat(currentUser())}");
    assertAllowed("${'%'.concat(myFunction())}");
    assertRejected("${otherFunction()}", "function 'otherFunction'");
    assertRejected("${'%'.toUpperCase()}", "method 'toUpperCase'");
  }

  @Test
  public void shouldKeepDefaultsWhenFunctionsAreConfigured() {
    // given
    configuration.setAllowedExpressionFunctionsInQueries("myFunction");

    // then
    assertAllowed("${now()}");
    assertAllowed("${dateTime().plusDays(1)}");
  }

  @Test
  public void shouldAcceptConfiguredBeans() {
    // given
    configuration.setAllowedExpressionBeansInQueries("someBean, otherBean");

    // then
    assertAllowed("${someBean}");
    assertAllowed("${otherBean}");
    assertAllowed("${someBean[0]}");
    assertRejected("${thirdBean}", "bean 'thirdBean'");
  }

  @Test
  public void shouldRequireMethodAndBeanToBeAllowedForBeanMethodInvocation() {
    // given
    configuration.setAllowedExpressionBeansInQueries("someBean");

    // then
    assertRejected("${someBean.someMethod()}", "method 'someMethod'");

    // and given
    configuration.setAllowedExpressionFunctionsInQueries("someMethod");

    // then
    assertAllowed("${someBean.someMethod()}");

    // and given
    configuration.setAllowedExpressionBeansInQueries(null);

    // then
    assertRejected("${someBean.someMethod()}", "bean 'someBean'");
  }

  @Test
  public void shouldAcceptConfiguredProperties() {
    // given
    configuration.setAllowedExpressionBeansInQueries("someBean");
    configuration.setAllowedExpressionPropertiesInQueries("name, id");

    // then
    assertAllowed("${someBean.name}");
    assertAllowed("${someBean.id}");
    assertRejected("${someBean.class}", "property 'class'");
  }

  @Test
  public void shouldRequireBeanAndPropertyToBeAllowedForPropertyAccess() {
    // given
    configuration.setAllowedExpressionPropertiesInQueries("name");

    // then
    assertRejected("${someBean.name}", "bean 'someBean'");
  }

  @Test
  public void shouldNotMixUpConfigurationOfFunctionsBeansAndProperties() {
    // given
    configuration.setAllowedExpressionFunctionsInQueries("name");
    configuration.setAllowedExpressionBeansInQueries("name");

    // then
    assertAllowed("${name()}");
    assertAllowed("${name}");
    assertRejected("${currentUser().name}", "property 'name'");
  }

  @Test
  public void shouldPickUpConfigurationChanges() {
    // given
    assertRejected("${someBean}", "bean 'someBean'");

    // when
    configuration.setAllowedExpressionBeansInQueries("someBean");

    // then
    assertAllowed("${someBean}");

    // when
    configuration.setAllowedExpressionBeansInQueries("");

    // then
    assertRejected("${someBean}", "bean 'someBean'");
  }

  protected void assertAllowed(String expression) {
    try {
      validator.validateExpression(expression);
    } catch (BadUserRequestException e) {
      fail("expression " + expression + " should be allowed, but was rejected: " + e.getMessage());
    }
  }

  protected void assertRejected(String expression, String expectedReason) {
    assertThatThrownBy(() -> validator.validateExpression(expression))
        .as("expression " + expression)
        .isInstanceOf(BadUserRequestException.class)
        .hasMessageContaining("is not allowed in query criteria")
        .hasMessageContaining(expectedReason);
  }

}
