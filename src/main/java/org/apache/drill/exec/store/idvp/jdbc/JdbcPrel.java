/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.drill.exec.store.idvp.jdbc;

import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.adapter.jdbc.JdbcImplementor;
import org.apache.calcite.plan.ConventionTraitDef;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.plan.volcano.RelSubset;
import org.apache.calcite.rel.AbstractRelNode;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelShuttleImpl;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.pretty.SqlPrettyWriter;
import org.apache.drill.exec.physical.base.PhysicalOperator;
import org.apache.drill.exec.planner.physical.PhysicalPlanCreator;
import org.apache.drill.exec.planner.physical.Prel;
import org.apache.drill.exec.planner.physical.visitor.PrelVisitor;
import org.apache.drill.exec.record.BatchSchema.SelectionVectorMode;

import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;

/**
 * Represents a JDBC Plan once the children nodes have been rewritten into SQL.
 */
public class JdbcPrel extends AbstractRelNode implements Prel {

    private final String sql;
    private final double rows;
    private final DrillJdbcConvention convention;

    JdbcPrel(RelOptCluster cluster, RelTraitSet traitSet, JdbcIntermediatePrel prel) {
        super(cluster, traitSet);
        final RelNode input = prel.getInput();
        //noinspection deprecation
        rows = input.getRows();
        convention = (DrillJdbcConvention) input.getTraitSet().getTrait(ConventionTraitDef.INSTANCE);

        // generate sql for tree.
        final SqlDialect dialect = convention.getPlugin().getDialect();
        final JdbcImplementor jdbcImplementor = new JdbcImplementor(
                dialect,
                (JavaTypeFactory) getCluster().getTypeFactory());
        final JdbcImplementor.Result result =
                jdbcImplementor.visitChild(0, input.accept(new SubsetRemover()));

        SqlPrettyWriter sqlWriter = new SqlPrettyWriter(dialect);
        sqlWriter.setSelectListItemsOnSeparateLines(false);
        sqlWriter.setQuoteAllIdentifiers(false);
        sqlWriter.setIndentation(0);

        result.asQuery().unparse(sqlWriter, 0, 0);

        sql = sqlWriter.toString();
        rowType = input.getRowType();
    }

    @Override
    public PhysicalOperator getPhysicalOperator(PhysicalPlanCreator creator) throws IOException {
        JdbcGroupScan output = new JdbcGroupScan(sql, convention.getPlugin(), rows);
        return creator.addMetadata(this, output);
    }

    @Override
    public RelWriter explainTerms(RelWriter pw) {
        return super.explainTerms(pw).item("sql", sql);
    }

    @Override
    public double estimateRowCount(RelMetadataQuery mq) {
        return rows;
    }

    @SuppressWarnings("NullableProblems")
    @Override
    public Iterator<Prel> iterator() {
        return Collections.emptyIterator();
    }

    @Override
    public <T, X, E extends Throwable> T accept(PrelVisitor<T, X, E> logicalVisitor, X value) throws E {
        return logicalVisitor.visitPrel(this, value);
    }

    @Override
    public SelectionVectorMode[] getSupportedEncodings() {
        return SelectionVectorMode.DEFAULT;
    }

    @Override
    public SelectionVectorMode getEncoding() {
        return SelectionVectorMode.NONE;
    }

    @Override
    public boolean needsFinalColumnReordering() {
        return false;
    }

    private class SubsetRemover extends RelShuttleImpl {

        @Override
        public RelNode visit(RelNode other) {
            if (other instanceof RelSubset) {
                return ((RelSubset) other).getBest().accept(this);
            } else {
                return super.visit(other);
            }
        }

    }
}
