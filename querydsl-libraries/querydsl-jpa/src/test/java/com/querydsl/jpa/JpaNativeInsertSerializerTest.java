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
package com.querydsl.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.querydsl.core.types.Expression;
import com.querydsl.core.types.Path;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.PathBuilder;
import com.querydsl.sql.Configuration;
import com.querydsl.sql.SQLExpressions;
import com.querydsl.sql.SQLTemplates;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import java.util.List;
import org.junit.Test;

public class JpaNativeInsertSerializerTest {

  @Table(name = "biz_msg")
  public static class BizMsg {
    @Column(name = "msg_type")
    private Integer msgType;

    @Column(name = "dest_phone")
    private String destPhone;

    @Column private String subject;
  }

  @Test
  public void schemaQualifiedFunctionCallSerializes() {
    var entity = new PathBuilder<>(BizMsg.class, "m");
    Path<?> destPhone = entity.get("destPhone");
    Path<?> subject = entity.get("subject");

    var encryptCall =
        SQLExpressions.function(
            String.class,
            "damo.dbo.encrypt_var_fpe",
            Expressions.constant("NIST_TEL"),
            Expressions.constant("01012345678"),
            Expressions.constant(""));

    var serializer = new JpaNativeInsertSerializer(new Configuration(SQLTemplates.DEFAULT));
    serializer.serializeInsert(
        BizMsg.class,
        List.of(destPhone, subject),
        List.<Expression<?>>of(encryptCall, Expressions.constant("[제목]")));

    var sql = serializer.toString();
    assertThat(sql).contains("insert into ").contains("biz_msg");
    assertThat(sql).contains("dest_phone").contains("subject");
    assertThat(sql).contains("damo.dbo.encrypt_var_fpe(?, ?, ?)");
    assertThat(sql).endsWith("?)");
    assertThat(serializer.getConstants()).containsExactly("NIST_TEL", "01012345678", "", "[제목]");
  }

  @Test
  public void stringFunctionUsesTypedBuilder() {
    var entity = new PathBuilder<>(BizMsg.class, "m");
    Path<?> destPhone = entity.get("destPhone");

    var encryptCall =
        SQLExpressions.stringFunction(
            "damo.dbo.encrypt_var_fpe",
            Expressions.constant("NIST_TEL"),
            Expressions.constant("01012345678"),
            Expressions.constant(""));

    var serializer = new JpaNativeInsertSerializer(new Configuration(SQLTemplates.DEFAULT));
    serializer.serializeInsert(
        BizMsg.class, List.of(destPhone), List.<Expression<?>>of(encryptCall));

    assertThat(serializer.toString()).contains("damo.dbo.encrypt_var_fpe(?, ?, ?)");
  }

  @Test
  public void simpleInsertWithoutFunction() {
    var entity = new PathBuilder<>(BizMsg.class, "m");
    Path<?> msgType = entity.get("msgType");
    Path<?> subject = entity.get("subject");

    var serializer = new JpaNativeInsertSerializer(new Configuration(SQLTemplates.DEFAULT));
    serializer.serializeInsert(
        BizMsg.class,
        List.of(msgType, subject),
        List.<Expression<?>>of(Expressions.constant(5), Expressions.constant("hello")));

    var sql = serializer.toString();
    assertThat(sql).contains("biz_msg");
    assertThat(sql).contains("msg_type, subject");
    assertThat(sql).endsWith("values (?, ?)");
  }

  @Test
  public void multiRowInsert() {
    var entity = new PathBuilder<>(BizMsg.class, "m");
    Path<?> msgType = entity.get("msgType");
    Path<?> subject = entity.get("subject");

    var serializer = new JpaNativeInsertSerializer(new Configuration(SQLTemplates.DEFAULT));
    serializer.serializeInsertRows(
        BizMsg.class,
        List.of(msgType, subject),
        List.of(
            List.<Expression<?>>of(Expressions.constant(5), Expressions.constant("a")),
            List.<Expression<?>>of(Expressions.constant(6), Expressions.constant("b"))));

    assertThat(serializer.toString()).endsWith("values (?, ?), (?, ?)");
  }

  @Test
  public void columnCountMismatchThrows() {
    var entity = new PathBuilder<>(BizMsg.class, "m");
    Path<?> msgType = entity.get("msgType");

    var serializer = new JpaNativeInsertSerializer(new Configuration(SQLTemplates.DEFAULT));

    assertThatThrownBy(
            () ->
                serializer.serializeInsert(
                    BizMsg.class,
                    List.of(msgType),
                    List.<Expression<?>>of(Expressions.constant(5), Expressions.constant("extra"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Column count");
  }
}
