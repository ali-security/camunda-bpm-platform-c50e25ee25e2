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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.camunda.bpm.engine.BadUserRequestException;
import org.camunda.bpm.engine.ProcessEngineConfiguration;
import org.camunda.bpm.engine.impl.el.CommandContextFunctions;
import org.camunda.bpm.engine.impl.el.DateTimeFunctions;
import org.camunda.bpm.engine.impl.util.StringUtil;
import org.camunda.bpm.engine.query.QueryExpressionValidator;
import org.camunda.bpm.impl.juel.AstDot;
import org.camunda.bpm.impl.juel.AstFunction;
import org.camunda.bpm.impl.juel.AstIdentifier;
import org.camunda.bpm.impl.juel.AstMethod;
import org.camunda.bpm.impl.juel.Builder;
import org.camunda.bpm.impl.juel.Node;
import org.camunda.bpm.impl.juel.Tree;
import org.camunda.bpm.impl.juel.TreeBuilder;
import org.camunda.bpm.impl.juel.TreeBuilderException;

/**
 * Validates query expressions against an allow-list before they are evaluated.
 *
 * <p>The expression is parsed with the JUEL parser (the same parser, and the same
 * method invocation support, as the {@link org.camunda.bpm.engine.impl.el.JuelExpressionManager})
 * and the resulting syntax tree is checked without evaluating anything. The following
 * elements are rejected unless they are allowed:</p>
 * <ul>
 *   <li>function calls, e.g. <code>now()</code></li>
 *   <li>method invocations, e.g. <code>dateTime().plusDays(1)</code></li>
 *   <li>dot-notation property access, e.g. <code>bean.name</code></li>
 *   <li>bean references, e.g. <code>bean</code></li>
 *   <li>method invocations in bracket notation, e.g. <code>bean['method'](1)</code></li>
 * </ul>
 * <p>Literals, operators and bracket-notation indexing, e.g. <code>list[0]</code>, are
 * always allowed.</p>
 *
 * <p>Built in, the functions {@code now}, {@code dateTime}, {@code currentUser} and
 * {@code currentUserGroups} and a set of date arithmetic and conversion methods of the
 * object returned by {@code dateTime()} are allowed. No beans and no properties are allowed.
 * The allow-list is extended with
 * {@link ProcessEngineConfiguration#setAllowedExpressionFunctionsInQueries(String)},
 * {@link ProcessEngineConfiguration#setAllowedExpressionBeansInQueries(String)} and
 * {@link ProcessEngineConfiguration#setAllowedExpressionPropertiesInQueries(String)}.
 * Functions and methods are matched by name only.</p>
 *
 * <p>The configuration is read each time an expression is validated.</p>
 */
public class DefaultQueryExpressionValidator implements QueryExpressionValidator {

  protected static final Set<String> DEFAULT_ALLOWED_FUNCTIONS = unmodifiableSet(
      DateTimeFunctions.NOW,
      DateTimeFunctions.DATE_TIME,
      CommandContextFunctions.CURRENT_USER,
      CommandContextFunctions.CURRENT_USER_GROUPS);

  protected static final Set<String> DEFAULT_ALLOWED_METHODS = unmodifiableSet(
      "plusYears", "plusMonths", "plusWeeks", "plusDays", "plusHours", "plusMinutes", "plusSeconds", "plusMillis",
      "minusYears", "minusMonths", "minusWeeks", "minusDays", "minusHours", "minusMinutes", "minusSeconds", "minusMillis",
      "withTimeAtStartOfDay", "withMillis", "withMillisOfSecond", "withSecondOfMinute", "withMinuteOfHour",
      "withHourOfDay", "withDayOfMonth", "withDayOfWeek", "withDayOfYear", "withMonthOfYear", "withYear",
      "toDate");

  protected static final TreeBuilder TREE_BUILDER = new Builder(Builder.Feature.METHOD_INVOCATIONS, Builder.Feature.VARARGS);

  protected final ProcessEngineConfiguration configuration;

  public DefaultQueryExpressionValidator(ProcessEngineConfiguration configuration) {
    this.configuration = configuration;
  }

  @Override
  public void validateExpression(String expressionText) {
    if (expressionText == null) {
      return;
    }

    Tree tree;
    try {
      tree = TREE_BUILDER.build(expressionText);
    } catch (TreeBuilderException e) {
      throw new BadUserRequestException("Expression '" + expressionText + "' is not allowed in query criteria: "
          + "it cannot be parsed.");
    }

    validateNode(tree.getRoot(), expressionText);
  }

  protected void validateNode(Node node, String expressionText) {
    if (node instanceof AstMethod) {
      validateMethod((AstMethod) node, expressionText);
      return;
    }

    if (node instanceof AstFunction) {
      String name = ((AstFunction) node).getName();
      ensureAllowed(name, DEFAULT_ALLOWED_FUNCTIONS, configuration.getAllowedExpressionFunctionsInQueries(),
          "function", "allowedExpressionFunctionsInQueries", expressionText);

    } else if (node instanceof AstIdentifier) {
      String name = ((AstIdentifier) node).getName();
      ensureAllowed(name, Collections.<String>emptySet(), configuration.getAllowedExpressionBeansInQueries(),
          "bean", "allowedExpressionBeansInQueries", expressionText);

    } else if (node instanceof AstDot) {
      String name = getPropertyName((AstDot) node);
      ensureAllowed(name, Collections.<String>emptySet(), configuration.getAllowedExpressionPropertiesInQueries(),
          "property", "allowedExpressionPropertiesInQueries", expressionText);
    }

    validateChildren(node, expressionText);
  }

  protected void validateMethod(AstMethod method, String expressionText) {
    Node property = method.getChild(0);
    if (!(property instanceof AstDot)) {
      throw reject(expressionText, "method invocations in bracket notation are not allowed");
    }

    String name = getPropertyName((AstDot) property);
    ensureAllowed(name, DEFAULT_ALLOWED_METHODS, configuration.getAllowedExpressionFunctionsInQueries(),
        "method", "allowedExpressionFunctionsInQueries", expressionText);

    // the name of the method is not a property access, so only the object
    // the method is invoked on and the method parameters are validated
    validateChildren(property, expressionText);
    validateNode(method.getChild(1), expressionText);
  }

  protected void validateChildren(Node node, String expressionText) {
    for (int i = 0; i < node.getCardinality(); i++) {
      validateNode(node.getChild(i), expressionText);
    }
  }

  protected void ensureAllowed(String name, Set<String> defaults, String configured, String kind,
                               String configurationProperty, String expressionText) {
    if (defaults.contains(name) || toSet(configured).contains(name)) {
      return;
    }

    throw reject(expressionText, kind + " '" + name + "' is not allowed. It can be allowed with the process engine"
        + " configuration property '" + configurationProperty + "'");
  }

  protected BadUserRequestException reject(String expressionText, String reason) {
    return new BadUserRequestException("Expression '" + expressionText + "' is not allowed in query criteria: "
        + reason + ".");
  }

  /**
   * The name of the property of a dot-notation node, which {@link AstDot} renders as ". name".
   */
  protected String getPropertyName(AstDot dot) {
    return dot.toString().substring(2);
  }

  protected Set<String> toSet(String commaSeparatedNames) {
    Set<String> names = new HashSet<>();
    if (commaSeparatedNames != null) {
      for (String name : StringUtil.split(commaSeparatedNames, ",")) {
        if (!name.isEmpty()) {
          names.add(name);
        }
      }
    }
    return names;
  }

  protected static Set<String> unmodifiableSet(String... names) {
    return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(names)));
  }

}
