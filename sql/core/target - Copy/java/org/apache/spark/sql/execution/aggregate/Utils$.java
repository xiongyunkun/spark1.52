package org.apache.spark.sql.execution.aggregate;
// no position
/**
 * Utility functions used by the query planner to convert our plan to new aggregation code path.
 */
public  class Utils$ {
  /**
   * Static reference to the singleton instance of this Scala object.
   */
  public static final Utils$ MODULE$ = null;
  public   Utils$ () { throw new RuntimeException(); }
  public  boolean supportsTungstenAggregate (scala.collection.Seq<org.apache.spark.sql.catalyst.expressions.Expression> groupingExpressions, scala.collection.Seq<org.apache.spark.sql.catalyst.expressions.Attribute> aggregateBufferAttributes) { throw new RuntimeException(); }
  public  scala.collection.Seq<org.apache.spark.sql.execution.SparkPlan> planAggregateWithoutDistinct (scala.collection.Seq<org.apache.spark.sql.catalyst.expressions.Expression> groupingExpressions, scala.collection.Seq<org.apache.spark.sql.catalyst.expressions.aggregate.AggregateExpression2> aggregateExpressions, scala.collection.immutable.Map<scala.Tuple2<org.apache.spark.sql.catalyst.expressions.aggregate.AggregateFunction2, java.lang.Object>, scala.Tuple2<org.apache.spark.sql.catalyst.expressions.aggregate.AggregateFunction2, org.apache.spark.sql.catalyst.expressions.Attribute>> aggregateFunctionMap, scala.collection.Seq<org.apache.spark.sql.catalyst.expressions.NamedExpression> resultExpressions, org.apache.spark.sql.execution.SparkPlan child) { throw new RuntimeException(); }
  public  scala.collection.Seq<org.apache.spark.sql.execution.SparkPlan> planAggregateWithOneDistinct (scala.collection.Seq<org.apache.spark.sql.catalyst.expressions.Expression> groupingExpressions, scala.collection.Seq<org.apache.spark.sql.catalyst.expressions.aggregate.AggregateExpression2> functionsWithDistinct, scala.collection.Seq<org.apache.spark.sql.catalyst.expressions.aggregate.AggregateExpression2> functionsWithoutDistinct, scala.collection.immutable.Map<scala.Tuple2<org.apache.spark.sql.catalyst.expressions.aggregate.AggregateFunction2, java.lang.Object>, scala.Tuple2<org.apache.spark.sql.catalyst.expressions.aggregate.AggregateFunction2, org.apache.spark.sql.catalyst.expressions.Attribute>> aggregateFunctionMap, scala.collection.Seq<org.apache.spark.sql.catalyst.expressions.NamedExpression> resultExpressions, org.apache.spark.sql.execution.SparkPlan child) { throw new RuntimeException(); }
}
