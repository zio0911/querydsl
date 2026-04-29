/*
 * Copyright 2015, The Querydsl Team (http://www.querydsl.com/team)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.querydsl.jpa.hibernate;

import com.querydsl.core.JoinType;
import com.querydsl.core.QueryException;
import com.querydsl.core.dml.InsertClause;
import com.querydsl.core.support.QueryMixin;
import com.querydsl.core.types.EntityPath;
import com.querydsl.core.types.Expression;
import com.querydsl.core.types.Path;
import com.querydsl.core.types.SubQueryExpression;
import com.querydsl.core.types.TemplateExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.HQLTemplates;
import com.querydsl.jpa.JPAQueryMixin;
import com.querydsl.jpa.JPQLSerializer;
import com.querydsl.jpa.JPQLTemplates;
import com.querydsl.jpa.JpaInsertNativeHelper;
import com.querydsl.jpa.JpaNativeInsertSerializer;
import com.querydsl.sql.Configuration;
import com.querydsl.sql.SQLTemplates;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hibernate.LockMode;
import org.hibernate.Session;
import org.hibernate.StatelessSession;
import org.hibernate.query.Query;

/**
 * UpdateClause implementation for Hibernate
 *
 * @author tiwe
 */
public class HibernateInsertClause implements InsertClause<HibernateInsertClause> {

  private final QueryMixin<?> queryMixin = new JPAQueryMixin<Void>();

  private final Map<Path<?>, Expression<?>> inserts = new LinkedHashMap<>();

  private final List<Path<?>> columns = new ArrayList<>();

  private final List<Object> values = new ArrayList<>();

  private SubQueryExpression<?> subQuery;

  private final SessionHolder session;

  private final JPQLTemplates templates;

  private final Map<Path<?>, LockMode> lockModes = new HashMap<>();

  public HibernateInsertClause(Session session, EntityPath<?> entity) {
    this(new DefaultSessionHolder(session), entity, HQLTemplates.DEFAULT);
  }

  public HibernateInsertClause(StatelessSession session, EntityPath<?> entity) {
    this(new StatelessSessionHolder(session), entity, HQLTemplates.DEFAULT);
  }

  public HibernateInsertClause(Session session, EntityPath<?> entity, JPQLTemplates templates) {
    this(new DefaultSessionHolder(session), entity, templates);
  }

  public HibernateInsertClause(
      SessionHolder session, EntityPath<?> entity, JPQLTemplates templates) {
    this.session = session;
    this.templates = templates;
    queryMixin.addJoin(JoinType.DEFAULT, entity);
  }

  @Override
  public long execute() {
    if (subQuery != null || !hasTemplateValue()) {
      var serializer = new JPQLSerializer(templates, null);
      serializer.serializeForInsert(
          queryMixin.getMetadata(),
          inserts.isEmpty() ? columns : inserts.keySet(),
          values,
          subQuery,
          inserts);

      Query query = session.createQuery(serializer.toString());
      for (Map.Entry<Path<?>, LockMode> entry : lockModes.entrySet()) {
        query.setLockMode(entry.getKey().toString(), entry.getValue());
      }
      HibernateUtil.setConstants(
          query, serializer.getConstants(), queryMixin.getMetadata().getParams());
      return query.executeUpdate();
    }

    var effectiveColumns = JpaInsertNativeHelper.effectiveColumns(inserts, columns);
    if (effectiveColumns.isEmpty()) {
      throw new IllegalStateException("No columns specified for insert");
    }
    var effectiveValues = JpaInsertNativeHelper.effectiveValues(inserts, values);

    var entityClass = queryMixin.getMetadata().getJoins().get(0).getTarget().getType();

    var serializer = new JpaNativeInsertSerializer(new Configuration(SQLTemplates.DEFAULT));
    serializer.serializeInsert(entityClass, effectiveColumns, effectiveValues);

    var sql = serializer.toString();
    var params =
        JpaInsertNativeHelper.resolveConstants(
            serializer.getConstants(), queryMixin.getMetadata().getParams());

    return session.doReturningWork(
        connection -> {
          try {
            return JpaInsertNativeHelper.executeUpdate(connection, sql, params);
          } catch (SQLException e) {
            throw new QueryException("Failed to execute insert", e);
          }
        });
  }

  /**
   * Whether any value expression is a {@link TemplateExpression} — typically a schema-qualified
   * function call from {@code SQLExpressions.function/stringFunction/numberFunction} that
   * Hibernate's HQL parser cannot type-check. When true, we route through the native SQL path to
   * bypass HQL semantic validation.
   */
  private boolean hasTemplateValue() {
    for (Object v : values) {
      if (v instanceof TemplateExpression) {
        return true;
      }
    }
    for (Expression<?> v : inserts.values()) {
      if (v instanceof TemplateExpression) {
        return true;
      }
    }
    return false;
  }

  @Override
  public HibernateInsertClause columns(Path<?>... columns) {
    this.columns.addAll(Arrays.asList(columns));
    return this;
  }

  @Override
  public HibernateInsertClause select(SubQueryExpression<?> sq) {
    subQuery = sq;
    return this;
  }

  /**
   * Set the lock mode for the given path.
   *
   * @return the current object
   */
  @SuppressWarnings("unchecked")
  public HibernateInsertClause setLockMode(Path<?> path, LockMode lockMode) {
    lockModes.put(path, lockMode);
    return this;
  }

  @Override
  public String toString() {
    var serializer = new JPQLSerializer(templates, null);
    serializer.serializeForInsert(
        queryMixin.getMetadata(),
        inserts.isEmpty() ? columns : inserts.keySet(),
        values,
        subQuery,
        inserts);
    return serializer.toString();
  }

  @Override
  public boolean isEmpty() {
    return columns.isEmpty();
  }

  @Override
  public <T> HibernateInsertClause set(Path<T> path, T value) {
    if (value != null) {
      inserts.put(path, Expressions.constant(value));
    } else {
      setNull(path);
    }
    return this;
  }

  @Override
  public <T> HibernateInsertClause set(Path<T> path, Expression<? extends T> expression) {
    if (expression != null) {
      inserts.put(path, expression);
    } else {
      setNull(path);
    }
    return this;
  }

  @Override
  public <T> HibernateInsertClause setNull(Path<T> path) {
    inserts.put(path, Expressions.nullExpression(path));
    return this;
  }

  @Override
  public HibernateInsertClause values(Object... v) {
    this.values.addAll(Arrays.asList(v));
    return this;
  }
}
