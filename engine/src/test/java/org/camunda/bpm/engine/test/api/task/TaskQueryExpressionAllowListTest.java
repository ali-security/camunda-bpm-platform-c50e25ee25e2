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
package org.camunda.bpm.engine.test.api.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.camunda.bpm.engine.BadUserRequestException;
import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.filter.Filter;
import org.camunda.bpm.engine.impl.TaskQueryImpl;
import org.camunda.bpm.engine.query.QueryExpressionValidator;
import org.camunda.bpm.engine.task.TaskQuery;
import org.camunda.bpm.engine.test.mock.Mocks;
import org.camunda.bpm.engine.test.util.PluggableProcessEngineTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the query expression allow-list, which is enabled by default, on top of the
 * switches for expressions in adhoc and in stored queries.
 */
public class TaskQueryExpressionAllowListTest extends PluggableProcessEngineTest {

  public static final String NOT_ALLOWED_MESSAGE = "is not allowed in query criteria";

  public static final String STATE_MANIPULATING_EXPRESSION =
      "${''.getClass().forName('" + TaskQueryExpressionAllowListTest.class.getName() + "').getField('MUTABLE_FIELD').setLong(null, 42)}";

  public static long MUTABLE_FIELD = 0;

  protected QueryExpressionValidator originalValidator;

  @Before
  public void setUp() {
    MUTABLE_FIELD = 0;
    originalValidator = processEngineConfiguration.getQueryExpressionValidator();
    processEngineConfiguration.setEnableExpressionsInAdhocQueries(true);
  }

  @After
  public void tearDown() {
    processEngineConfiguration.setEnableExpressionsInAdhocQueries(false);
    processEngineConfiguration.setEnableExpressionsInStoredQueries(true);
    processEngineConfiguration.setQueryExpressionAllowListEnabled(true);
    processEngineConfiguration.setAllowedExpressionFunctionsInQueries(null);
    processEngineConfiguration.setAllowedExpressionBeansInQueries(null);
    processEngineConfiguration.setAllowedExpressionPropertiesInQueries(null);
    processEngineConfiguration.setQueryExpressionValidator(originalValidator);
    Mocks.reset();
    for (Filter filter : filterService.createFilterQuery().list()) {
      filterService.deleteFilter(filter.getId());
    }
  }

  @Test
  public void testDefaultSettings() {
    assertThat(processEngineConfiguration.isQueryExpressionAllowListEnabled()).isTrue();
    assertThat(processEngineConfiguration.getAllowedExpressionFunctionsInQueries()).isNull();
    assertThat(processEngineConfiguration.getAllowedExpressionBeansInQueries()).isNull();
    assertThat(processEngineConfiguration.getAllowedExpressionPropertiesInQueries()).isNull();
    assertThat(processEngineConfiguration.getQueryExpressionValidator()).isNotNull();
  }

  @Test
  public void testStateManipulatingExpressionIsEvaluatedWithoutAllowList() {
    // this makes sure that the tests below do not pass vacuously
    processEngineConfiguration.setQueryExpressionAllowListEnabled(false);

    // when the expression is evaluated, it sets the field and then fails to be applied to the query
    assertThatThrownBy(() -> taskService.createTaskQuery().taskOwnerExpression(STATE_MANIPULATING_EXPRESSION).count())
        .isInstanceOf(ProcessEngineException.class);

    assertThat(MUTABLE_FIELD).isEqualTo(42);
  }

  @Test
  public void testNotAllowedExpressionsFail() {
    assertFails(taskService.createTaskQuery().dueAfterExpression(STATE_MANIPULATING_EXPRESSION));
    assertFails(taskService.createTaskQuery().dueBeforeExpression(STATE_MANIPULATING_EXPRESSION));
    assertFails(taskService.createTaskQuery().dueDateExpression(STATE_MANIPULATING_EXPRESSION));
    assertFails(taskService.createTaskQuery().followUpAfterExpression(STATE_MANIPULATING_EXPRESSION));
    assertFails(taskService.createTaskQuery().followUpBeforeExpression(STATE_MANIPULATING_EXPRESSION));
    assertFails(taskService.createTaskQuery().followUpBeforeOrNotExistentExpression(STATE_MANIPULATING_EXPRESSION));
    assertFails(taskService.createTaskQuery().followUpDateExpression(STATE_MANIPULATING_EXPRESSION));
    assertFails(taskService.createTaskQuery().taskAssigneeExpression(STATE_MANIPULATING_EXPRESSION));
    assertFails(taskService.createTaskQuery().taskAssigneeLikeExpression(STATE_MANIPULATING_EXPRESSION));
    assertFails(taskService.createTaskQuery().taskCandidateGroupExpression(STATE_MANIPULATING_EXPRESSION));
    assertFails(taskService.createTaskQuery().taskCandidateGroupInExpression(STATE_MANIPULATING_EXPRESSION));
    assertFails(taskService.createTaskQuery().taskCandidateUserExpression(STATE_MANIPULATING_EXPRESSION));
    assertFails(taskService.createTaskQuery().taskCreatedAfterExpression(STATE_MANIPULATING_EXPRESSION));
    assertFails(taskService.createTaskQuery().taskCreatedBeforeExpression(STATE_MANIPULATING_EXPRESSION));
    assertFails(taskService.createTaskQuery().taskOwnerExpression(STATE_MANIPULATING_EXPRESSION));
  }

  @Test
  public void testNotAllowedExpressionFailsWithBadUserRequestException() {
    TaskQuery query = taskService.createTaskQuery().taskAssigneeExpression("${someBean}");

    assertThatThrownBy(query::list)
        .isInstanceOf(BadUserRequestException.class)
        .hasMessageContaining("Expression '${someBean}' is not allowed in query criteria")
        .hasMessageContaining("bean 'someBean'");
  }

  @Test
  public void testNotAllowedExpressionInOrQueryFails() {
    assertFails(taskService.createTaskQuery()
        .or()
          .dueAfterExpression(STATE_MANIPULATING_EXPRESSION)
          .taskName("task")
        .endOr());

    assertFails(taskService.createTaskQuery()
        .taskName("task")
        .or()
          .taskAssigneeExpression("${someBean}")
          .taskOwner("owner")
        .endOr());
  }

  @Test
  public void testNotAllowedExpressionInAddedOrQueryFails() {
    // the REST API builds Or queries with the no-argument constructor and attaches them via addOrQuery
    TaskQueryImpl query = (TaskQueryImpl) taskService.createTaskQuery();
    TaskQueryImpl orQuery = new TaskQueryImpl();
    orQuery.setOrQueryActive();
    orQuery.dueAfterExpression(STATE_MANIPULATING_EXPRESSION);
    orQuery.taskName("task");
    query.addOrQuery(orQuery);

    assertFails(query);
  }

  @Test
  public void testAllowedExpressionsSucceed() {
    assertThat(taskService.createTaskQuery().dueAfterExpression("${now()}").count()).isZero();
    assertThat(taskService.createTaskQuery().dueAfterExpression("${dateTime().plusDays(1).withTimeAtStartOfDay()}").count()).isZero();
    assertThat(taskService.createTaskQuery().taskAssigneeExpression("${'user'}").list()).isEmpty();
    assertThat(taskService.createTaskQuery().taskAssigneeExpression("plain").list()).isEmpty();
    assertThat(taskService.createTaskQuery()
        .or()
          .dueAfterExpression("${now()}")
          .taskName("task")
        .endOr()
        .count()).isZero();
  }

  @Test
  public void testAllowListAppliesOnTopOfDisabledAdhocExpressions() {
    processEngineConfiguration.setEnableExpressionsInAdhocQueries(false);

    // the allowed expression is rejected by the switch
    assertThatThrownBy(() -> taskService.createTaskQuery().dueAfterExpression("${now()}").count())
        .isInstanceOf(BadUserRequestException.class)
        .hasMessageContaining("Expressions are forbidden in adhoc queries");
  }

  @Test
  public void testConfiguredFunctionIsAllowed() {
    // given
    TaskQuery query = taskService.createTaskQuery().taskAssigneeExpression("${'a'.concat('b')}");
    assertFails(query);

    // when
    processEngineConfiguration.setAllowedExpressionFunctionsInQueries("concat");

    // then
    assertThat(query.list()).isEmpty();
  }

  @Test
  public void testConfiguredBeanIsAllowed() {
    // given
    Mocks.register("someBean", "someValue");
    TaskQuery query = taskService.createTaskQuery().taskAssigneeExpression("${someBean}");
    assertFails(query);

    // when
    processEngineConfiguration.setAllowedExpressionBeansInQueries("someBean");

    // then
    assertThat(query.list()).isEmpty();
  }

  @Test
  public void testDisabledAllowListAllowsAnyExpression() {
    // given
    Mocks.register("someBean", "someValue");
    processEngineConfiguration.setQueryExpressionAllowListEnabled(false);

    // then
    assertThat(taskService.createTaskQuery().taskAssigneeExpression("${someBean}").list()).isEmpty();
    assertThat(taskService.createTaskQuery().taskAssigneeExpression("${'a'.concat('b')}").list()).isEmpty();
  }

  @Test
  public void testCustomValidator() {
    // given
    StringBuilder validated = new StringBuilder();
    processEngineConfiguration.setQueryExpressionValidator(expressionText -> {
      validated.append(expressionText);
      throw new BadUserRequestException("custom validation failed");
    });

    // then
    assertThatThrownBy(() -> taskService.createTaskQuery().dueAfterExpression("${now()}").count())
        .isInstanceOf(BadUserRequestException.class)
        .hasMessage("custom validation failed");
    assertThat(validated.toString()).isEqualTo("${now()}");
  }

  @Test
  public void testNotAllowedExpressionInStoredFilterFailsOnExecution() {
    // given a filter that is saved with a not allowed expression, e.g. before the allow-list was in place
    Filter filter = filterService.newTaskFilter("filter");
    filter.setQuery(taskService.createTaskQuery().dueAfterExpression(STATE_MANIPULATING_EXPRESSION));
    filterService.saveFilter(filter);

    // then it cannot be executed
    assertThatThrownBy(() -> filterService.list(filter.getId()))
        .isInstanceOf(BadUserRequestException.class)
        .hasMessageContaining(NOT_ALLOWED_MESSAGE);
    assertThatThrownBy(() -> filterService.count(filter.getId()))
        .isInstanceOf(BadUserRequestException.class)
        .hasMessageContaining(NOT_ALLOWED_MESSAGE);
    assertThatThrownBy(() -> filterService.singleResult(filter.getId()))
        .isInstanceOf(BadUserRequestException.class)
        .hasMessageContaining(NOT_ALLOWED_MESSAGE);
    assertThat(MUTABLE_FIELD).isZero();
  }

  @Test
  public void testNotAllowedExpressionInExtendedStoredFilterFailsOnExecution() {
    // given
    Filter filter = filterService.newTaskFilter("filter");
    filter.setQuery(taskService.createTaskQuery().dueAfterExpression(STATE_MANIPULATING_EXPRESSION));
    filterService.saveFilter(filter);

    // then the filter cannot be executed, whether it is extended or not
    assertThatThrownBy(() -> filterService.list(filter.getId(), taskService.createTaskQuery().taskName("task")))
        .isInstanceOf(BadUserRequestException.class)
        .hasMessageContaining(NOT_ALLOWED_MESSAGE);
    assertThat(MUTABLE_FIELD).isZero();
  }

  @Test
  public void testNotAllowedExpressionInExtendingQueryFails() {
    // given a stored filter with an allowed expression
    Filter filter = filterService.newTaskFilter("filter");
    filter.setQuery(taskService.createTaskQuery().dueAfterExpression("${now()}"));
    filterService.saveFilter(filter);

    // then it cannot be extended with a not allowed expression, in an Or query either
    assertThatThrownBy(() -> filterService.list(filter.getId(),
        taskService.createTaskQuery().taskAssigneeExpression(STATE_MANIPULATING_EXPRESSION)))
        .isInstanceOf(BadUserRequestException.class)
        .hasMessageContaining(NOT_ALLOWED_MESSAGE);
    assertThatThrownBy(() -> filterService.list(filter.getId(),
        taskService.createTaskQuery().or().taskAssigneeExpression(STATE_MANIPULATING_EXPRESSION).taskName("task").endOr()))
        .isInstanceOf(BadUserRequestException.class)
        .hasMessageContaining(NOT_ALLOWED_MESSAGE);
    assertThat(MUTABLE_FIELD).isZero();
  }

  @Test
  public void testAllowedExpressionInStoredFilterSucceeds() {
    // given
    Filter filter = filterService.newTaskFilter("filter");
    filter.setQuery(taskService.createTaskQuery()
        .dueAfterExpression("${dateTime().plusDays(2).withTimeAtStartOfDay()}")
        .taskAssigneeExpression("${currentUser()}"));
    filterService.saveFilter(filter);
    identityService.setAuthenticatedUserId("user");

    try {
      // then
      assertThat(filterService.list(filter.getId())).isEmpty();
      assertThat(filterService.count(filter.getId())).isZero();
    } finally {
      identityService.clearAuthentication();
    }
  }

  @Test
  public void testAllowListAppliesOnTopOfDisabledStoredExpressions() {
    // given
    Filter filter = filterService.newTaskFilter("filter");
    filter.setQuery(taskService.createTaskQuery().dueAfterExpression("${now()}"));
    filterService.saveFilter(filter);

    // when
    processEngineConfiguration.setEnableExpressionsInStoredQueries(false);

    // then
    assertThatThrownBy(() -> filterService.list(filter.getId()))
        .isInstanceOf(BadUserRequestException.class)
        .hasMessageContaining("Expressions are forbidden in stored queries");
  }

  protected void assertFails(TaskQuery query) {
    assertThatThrownBy(query::list)
        .isInstanceOf(BadUserRequestException.class)
        .hasMessageContaining(NOT_ALLOWED_MESSAGE);
    assertThat(MUTABLE_FIELD).isZero();

    assertThatThrownBy(query::count)
        .isInstanceOf(BadUserRequestException.class)
        .hasMessageContaining(NOT_ALLOWED_MESSAGE);
    assertThat(MUTABLE_FIELD).isZero();
  }

}
