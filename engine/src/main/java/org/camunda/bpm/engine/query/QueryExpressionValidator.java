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
package org.camunda.bpm.engine.query;

import org.camunda.bpm.engine.BadUserRequestException;

/**
 * Validates the text of an expression that is part of a query, e.g. the argument of
 * {@link org.camunda.bpm.engine.task.TaskQuery#dueAfterExpression(String)}, before the
 * expression engine evaluates it. The validation is a separate step that runs regardless
 * of the configured {@link org.camunda.bpm.engine.impl.el.ExpressionManager}.
 *
 * <p>Implementations should reject function calls, method invocations, dot-notation
 * property access and bean references that are not permitted, with a descriptive message.
 * Bracket-notation indexing such as <code>map['key']</code> or <code>list[0]</code> should
 * generally be allowed.</p>
 *
 * <p>The default implementation parses the expression with the JUEL parser and checks it
 * against an allow-list, see
 * {@link org.camunda.bpm.engine.ProcessEngineConfiguration#setQueryExpressionAllowListEnabled(boolean)}.
 * It matches the JUEL based expression managers. A different expression language needs
 * a matching validator, which can be registered with
 * {@link org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl#setQueryExpressionValidator(QueryExpressionValidator)}.</p>
 */
public interface QueryExpressionValidator {

  /**
   * Validates the expression text.
   *
   * @param expressionText the full expression text as supplied by the caller,
   *                       e.g. <code>"${currentUser()}"</code>
   * @throws BadUserRequestException if the expression is not permitted
   */
  void validateExpression(String expressionText);

}
