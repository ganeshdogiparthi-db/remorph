package com.databricks.labs.remorph.parsers
package tsql

import com.databricks.labs.remorph.{intermediate => ir}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar

class TSqlRelationBuilderSpec
    extends AnyWordSpec
    with TSqlParserTestCommon
    with SetOperationBehaviors[TSqlParser]
    with Matchers
    with MockitoSugar
    with ir.IRHelpers {

  override protected def astBuilder: TSqlRelationBuilder = vc.relationBuilder

  "TSqlRelationBuilder" should {

    "translate query with no FROM clause" in {
      example("", _.selectOptionalClauses(), ir.NoTable)
    }

    "translate FROM clauses" should {
      "FROM some_table" in {
        example("FROM some_table", _.fromClause(), namedTable("some_table"))
      }
      "FROM some_schema.some_table" in {
        example("FROM some_schema.some_table", _.fromClause(), namedTable("some_schema.some_table"))
      }
      "FROM some_server..some_table" in {
        example("FROM some_server..some_table", _.fromClause(), namedTable("some_server..some_table"))
      }
      "FROM t1, t2, t3" in {
        example(
          "FROM t1, t2, t3",
          _.fromClause(),
          ir.Join(
            ir.Join(
              namedTable("t1"),
              namedTable("t2"),
              None,
              ir.CrossJoin,
              Seq(),
              ir.JoinDataType(is_left_struct = false, is_right_struct = false)),
            namedTable("t3"),
            None,
            ir.CrossJoin,
            Seq(),
            ir.JoinDataType(is_left_struct = false, is_right_struct = false)))
      }
    }

    "FROM some_table WHERE 1=1" in {
      example(
        "FROM some_table WHERE 1=1",
        _.selectOptionalClauses(),
        ir.Filter(namedTable("some_table"), ir.Equals(ir.Literal(1), ir.Literal(1))))
    }

    "FROM some_table GROUP BY some_column" in {
      example(
        "FROM some_table GROUP BY some_column",
        _.selectOptionalClauses(),
        ir.Aggregate(
          child = namedTable("some_table"),
          group_type = ir.GroupBy,
          grouping_expressions = Seq(simplyNamedColumn("some_column")),
          pivot = None))
    }

    "translate ORDER BY clauses" should {
      "FROM some_table ORDER BY some_column" in {
        example(
          "FROM some_table ORDER BY some_column",
          _.selectOptionalClauses(),
          ir.Sort(
            namedTable("some_table"),
            Seq(ir.SortOrder(simplyNamedColumn("some_column"), ir.Ascending, ir.SortNullsUnspecified)),
            is_global = false))
      }
      "FROM some_table ORDER BY some_column ASC" in {
        example(
          "FROM some_table ORDER BY some_column ASC",
          _.selectOptionalClauses(),
          ir.Sort(
            namedTable("some_table"),
            Seq(ir.SortOrder(simplyNamedColumn("some_column"), ir.Ascending, ir.SortNullsUnspecified)),
            is_global = false))
      }
      "FROM some_table ORDER BY some_column DESC" in {
        example(
          "FROM some_table ORDER BY some_column DESC",
          _.selectOptionalClauses(),
          ir.Sort(
            namedTable("some_table"),
            Seq(ir.SortOrder(simplyNamedColumn("some_column"), ir.Descending, ir.SortNullsUnspecified)),
            is_global = false))
      }
    }

    "translate combinations of the above" should {
      "FROM some_table WHERE 1=1 GROUP BY some_column" in {
        example(
          "FROM some_table WHERE 1=1 GROUP BY some_column",
          _.selectOptionalClauses(),
          ir.Aggregate(
            child = ir.Filter(namedTable("some_table"), ir.Equals(ir.Literal(1), ir.Literal(1))),
            group_type = ir.GroupBy,
            grouping_expressions = Seq(simplyNamedColumn("some_column")),
            pivot = None))
      }
      "FROM some_table WHERE 1=1 GROUP BY some_column ORDER BY some_column" in {
        example(
          "FROM some_table WHERE 1=1 GROUP BY some_column ORDER BY some_column",
          _.selectOptionalClauses(),
          ir.Sort(
            ir.Aggregate(
              child = ir.Filter(namedTable("some_table"), ir.Equals(ir.Literal(1), ir.Literal(1))),
              group_type = ir.GroupBy,
              grouping_expressions = Seq(simplyNamedColumn("some_column")),
              pivot = None),
            Seq(ir.SortOrder(simplyNamedColumn("some_column"), ir.Ascending, ir.SortNullsUnspecified)),
            is_global = false))
      }
    }

    "WITH a (b, c) AS (SELECT x, y FROM d)" in {
      example(
        "WITH a (b, c) AS (SELECT x, y FROM d)",
        _.withExpression(),
        ir.SubqueryAlias(
          ir.Project(namedTable("d"), Seq(simplyNamedColumn("x"), simplyNamedColumn("y"))),
          ir.Id("a"),
          Seq(ir.Id("b"), ir.Id("c"))))
    }

    "SELECT DISTINCT a, b AS bb FROM t" in {
      example(
        "SELECT DISTINCT a, b AS bb FROM t",
        _.selectStatement(),
        ir.Project(
          ir.Deduplicate(
            namedTable("t"),
            column_names = Seq(ir.Id("a"), ir.Id("bb")),
            all_columns_as_keys = false,
            within_watermark = false),
          Seq(simplyNamedColumn("a"), ir.Alias(simplyNamedColumn("b"), ir.Id("bb")))))
    }

    behave like setOperationsAreTranslated(_.queryExpression())

    "SELECT a, b AS bb FROM (SELECT x, y FROM d) AS t (aliasA, 'aliasB')" in {
      example(
        "SELECT a, b AS bb FROM (SELECT x, y FROM d) AS t (aliasA, 'aliasB')",
        _.selectStatement(),
        ir.Project(
          ir.TableAlias(
            ColumnAliases(
              ir.Project(
                ir.NamedTable("d", Map(), is_streaming = false),
                Seq(ir.Column(None, ir.Id("x")), ir.Column(None, ir.Id("y")))),
              Seq(ir.Id("aliasA"), ir.Id("aliasB"))),
            "t"),
          Seq(ir.Column(None, ir.Id("a")), ir.Alias(ir.Column(None, ir.Id("b")), ir.Id("bb")))))
    }
  }
}
