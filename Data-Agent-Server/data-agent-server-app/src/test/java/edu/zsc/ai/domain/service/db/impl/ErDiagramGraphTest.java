package edu.zsc.ai.domain.service.db.impl;

import edu.zsc.ai.plugin.model.metadata.ConstraintMetadata;
import edu.zsc.ai.plugin.model.metadata.IndexMetadata;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErDiagramGraphTest {

    @Test
    void keepsSameSchemaForeignKeyOnTheOpenedScope() {
        ErDiagramGraph.AssembleResult graph = ErDiagramGraph.assemble(
                schemaScope("sales", "hr"),
                local("orders", "users"),
                List.of(key(null, "HR", "Users", "id", null, "hr", "orders", "user_id", "fk_orders_user")));

        assertTrue(graph.externalTables().isEmpty());
        assertFalse(graph.externalTruncated());
        assertEquals(1, graph.relations().size());
        ErDiagramGraph.Relation relation = graph.relations().get(0);
        assertEquals("orders", relation.from().table());
        assertEquals("hr", relation.from().schema());
        assertEquals("users", relation.to().table());
        assertEquals("hr", relation.to().schema());
        assertFalse(relation.to().external());
        assertEquals(ErDiagramGraph.Cardinality.ONE_TO_MANY, relation.cardinality());
        assertEquals(List.of("user_id"), relation.fromColumns());
        assertEquals(List.of("id"), relation.toColumns());
    }

    @Test
    void addsParentTableFromAnotherSchema() {
        ErDiagramGraph.AssembleResult graph = ErDiagramGraph.assemble(
                schemaScope(null, "app"),
                local("orders"),
                List.of(key(null, "dict", "users", "id", null, "app", "orders", "user_id", "fk_user")));

        assertEquals(1, graph.externalTables().size());
        ErDiagramGraph.Endpoint parent = graph.externalTables().get(0);
        assertEquals("users", parent.table());
        assertEquals("dict", parent.schema());
        assertTrue(parent.external());
        ErDiagramGraph.Relation relation = graph.relations().get(0);
        assertEquals("app", relation.from().schema());
        assertEquals("orders", relation.from().table());
        assertEquals("dict", relation.to().schema());
        assertEquals("users", relation.to().table());
    }

    @Test
    void sameTableNameInAnotherSchemaStaysDistinct() {
        ErDiagramGraph.AssembleResult graph = ErDiagramGraph.assemble(
                schemaScope(null, "app"),
                local("users"),
                List.of(key(null, "audit", "users", "id", null, "app", "users", "parent_id", "fk_parent")));

        assertEquals(1, graph.externalTables().size());
        assertEquals("audit", graph.externalTables().get(0).schema());
        assertEquals("users", graph.relations().get(0).from().table());
        assertFalse(graph.relations().get(0).from().external());
        assertTrue(graph.relations().get(0).to().external());
    }

    @Test
    void addsChildTableThatReferencesTheOpenedSchema() {
        ErDiagramGraph.AssembleResult graph = ErDiagramGraph.assemble(
                schemaScope(null, "app"),
                local("users"),
                List.of(key(null, "app", "users", "id", null, "billing", "invoices", "user_id", "fk_invoice_user")));

        assertEquals("invoices", graph.externalTables().get(0).table());
        assertEquals("billing", graph.externalTables().get(0).schema());
        assertEquals("invoices", graph.relations().get(0).from().table());
        assertEquals("users", graph.relations().get(0).to().table());
        assertFalse(graph.relations().get(0).to().external());
    }

    @Test
    void mysqlCrossCatalogUsesTheDatabaseAsTheNamespace() {
        ErDiagramGraph.AssembleResult graph = ErDiagramGraph.assemble(
                new ErDiagramGraph.Scope("shop", null, false),
                local("orders"),
                List.of(key("crm", null, "users", "id", "shop", null, "orders", "user_id", "fk_user")));

        assertEquals("crm", graph.externalTables().get(0).catalog());
        assertNull(graph.externalTables().get(0).schema());
        assertEquals("shop", graph.relations().get(0).from().catalog());
        assertEquals("crm", graph.relations().get(0).to().catalog());
    }

    @Test
    void mysqlSameCatalogStaysInsideTheOpenedDatabase() {
        ErDiagramGraph.AssembleResult graph = ErDiagramGraph.assemble(
                new ErDiagramGraph.Scope("shop", null, false),
                local("orders", "users"),
                List.of(key("shop", null, "users", "id", "shop", null, "orders", "user_id", "fk_user")));

        assertTrue(graph.externalTables().isEmpty());
        assertEquals("users", graph.relations().get(0).to().table());
        assertFalse(graph.relations().get(0).to().external());
    }

    @Test
    void dropsAKeyWhoseSchemaWasNotReportedAndWhoseTableIsNotLocal() {
        ErDiagramGraph.AssembleResult graph = ErDiagramGraph.assemble(
                schemaScope(null, "app"),
                local("orders"),
                List.of(key(null, null, "users", "id", null, "app", "orders", "user_id", "fk_user")));

        assertTrue(graph.externalTables().isEmpty());
        assertTrue(graph.relations().isEmpty());
    }

    @Test
    void groupsCompositeForeignKeyColumnsAndCollapsesImportedCopies() {
        ErDiagramGraph.KeyRow tenant = key(null, "dict", "users", "tenant_id", null, "app", "orders", "tenant_id", "fk_user", 2);
        ErDiagramGraph.KeyRow first = key(null, "dict", "users", "id", null, "app", "orders", "user_id", "fk_user", 1);
        ErDiagramGraph.AssembleResult graph = ErDiagramGraph.assemble(
                schemaScope(null, "app"),
                local("orders"),
                List.of(tenant, first, first));

        assertEquals(1, graph.externalTables().size());
        assertEquals(1, graph.relations().size());
        ErDiagramGraph.Relation relation = graph.relations().get(0);
        assertEquals("user_id", relation.fromColumn());
        assertEquals(List.of("user_id", "tenant_id"), relation.fromColumns());
        assertEquals(List.of("id", "tenant_id"), relation.toColumns());
        assertEquals(ErDiagramGraph.Cardinality.ONE_TO_MANY, relation.cardinality());
    }

    @Test
    void capsExternalTablesAndDropsRelationsPastTheCap() {
        List<ErDiagramGraph.KeyRow> keys = new ArrayList<>();
        for (int i = 0; i <= ErDiagramGraph.EXTERNAL_TABLE_LIMIT; i++) {
            keys.add(key(null, "s" + i, "parent", "id", null, "app", "orders", "ref_" + i, "fk_" + i));
        }
        ErDiagramGraph.AssembleResult graph = ErDiagramGraph.assemble(schemaScope(null, "app"), local("orders"), keys);

        assertTrue(graph.externalTruncated());
        assertEquals(ErDiagramGraph.EXTERNAL_TABLE_LIMIT, graph.externalTables().size());
        assertEquals(ErDiagramGraph.EXTERNAL_TABLE_LIMIT, graph.relations().size());
        assertEquals("s0", graph.externalTables().get(0).schema());
        assertEquals("s" + (ErDiagramGraph.EXTERNAL_TABLE_LIMIT - 1),
                graph.externalTables().get(ErDiagramGraph.EXTERNAL_TABLE_LIMIT - 1).schema());
    }

    @Test
    void dropsASameSchemaTableThatWasNotLoaded() {
        ErDiagramGraph.AssembleResult graph = ErDiagramGraph.assemble(
                schemaScope(null, "app"),
                local("orders"),
                List.of(key(null, "app", "archived", "id", null, "app", "orders", "arch_id", "fk_arch")));

        assertTrue(graph.externalTables().isEmpty());
        assertTrue(graph.relations().isEmpty());
    }

    @Test
    void dictionaryForeignKeyUsesTheReferencedSchema() {
        List<ErDiagramGraph.KeyRow> keys = ErDiagramGraph.keysFromConstraints(null, "app", "orders", true, List.of(
                constraint("fk_user", "R", List.of("user_id", "tenant_id"), "dict", "users", List.of("id", "tenant_id")),
                constraint("pk_orders", "P", List.of("id"), null, null, List.of()),
                constraint("fk_local", "R", List.of("status_id"), "app", "statuses", List.of("id"))));

        ErDiagramGraph.AssembleResult graph = ErDiagramGraph.assemble(schemaScope(null, "app"), local("orders"), keys);

        assertEquals(1, graph.externalTables().size());
        assertEquals("dict", graph.externalTables().get(0).schema());
        assertEquals("users", graph.externalTables().get(0).table());
        assertEquals(1, graph.relations().size());
        assertEquals(List.of("user_id", "tenant_id"), graph.relations().get(0).fromColumns());
        assertEquals(List.of("id", "tenant_id"), graph.relations().get(0).toColumns());
    }

    @Test
    void stubColumnsKeepTheForeignKeyWhenTableMetadataIsMissing() {
        ErDiagramGraph.Endpoint parent = new ErDiagramGraph.Endpoint(null, "dict", "users", true);
        ErDiagramGraph.Endpoint child = new ErDiagramGraph.Endpoint(null, "app", "orders", false);
        ErDiagramGraph.Relation relation = new ErDiagramGraph.Relation(
                "fk_user", child, List.of("user_id"), parent, List.of("id"), ErDiagramGraph.Cardinality.ONE_TO_MANY);

        List<ErDiagramGraph.StubColumn> columns = ErDiagramGraph.stubColumns(parent, List.of(relation));

        assertEquals(1, columns.size());
        assertEquals("id", columns.get(0).name());
        assertTrue(columns.get(0).primaryKey());
    }

    @Test
    void stubColumnsKeepEveryColumnOfACompositeForeignKey() {
        ErDiagramGraph.Endpoint parent = new ErDiagramGraph.Endpoint(null, "dict", "users", true);
        ErDiagramGraph.Endpoint child = new ErDiagramGraph.Endpoint(null, "app", "orders", false);
        ErDiagramGraph.Relation relation = new ErDiagramGraph.Relation(
                "fk_user", child, List.of("user_id", "tenant_id"), parent, List.of("id", "tenant_id"),
                ErDiagramGraph.Cardinality.ONE_TO_MANY);

        List<ErDiagramGraph.StubColumn> columns = ErDiagramGraph.stubColumns(parent, List.of(relation));

        assertEquals(List.of("id", "tenant_id"), columns.stream().map(ErDiagramGraph.StubColumn::name).toList());
        assertTrue(columns.stream().allMatch(ErDiagramGraph.StubColumn::primaryKey));
    }

    @Test
    void uniqueForeignKeyIsOneToOne() {
        ErDiagramGraph.AssembleResult graph = ErDiagramGraph.assemble(
                schemaScope(null, "app"),
                local("profiles", "users"),
                List.of(key(null, "app", "users", "id", null, "app", "profiles", "user_id", "fk_user")),
                List.of(unique(null, "app", "profiles", "user_id")));

        assertEquals(ErDiagramGraph.Cardinality.ONE_TO_ONE, graph.relations().get(0).cardinality());
    }

    @Test
    void primaryKeyOnTheForeignKeyColumnsIsOneToOne() {
        ErDiagramGraph.AssembleResult graph = ErDiagramGraph.assemble(
                schemaScope(null, "app"),
                local("profiles", "users"),
                List.of(key(null, "app", "users", "id", null, "app", "profiles", "user_id", "fk_user")),
                List.of(unique(null, "app", "profiles", "User_Id")));

        assertEquals(ErDiagramGraph.Cardinality.ONE_TO_ONE, graph.relations().get(0).cardinality());
    }

    @Test
    void uniqueSubsetOfACompositeForeignKeyIsOneToOne() {
        ErDiagramGraph.AssembleResult graph = ErDiagramGraph.assemble(
                schemaScope(null, "app"),
                local("orders", "users"),
                List.of(
                        key(null, "app", "users", "id", null, "app", "orders", "user_id", "fk_user", 1),
                        key(null, "app", "users", "tenant_id", null, "app", "orders", "tenant_id", "fk_user", 2)),
                List.of(unique(null, "app", "orders", "user_id")));

        assertEquals(1, graph.relations().size());
        assertEquals(ErDiagramGraph.Cardinality.ONE_TO_ONE, graph.relations().get(0).cardinality());
    }

    @Test
    void widerUniqueKeyLeavesANarrowerForeignKeyOneToMany() {
        ErDiagramGraph.AssembleResult graph = ErDiagramGraph.assemble(
                schemaScope(null, "app"),
                local("orders", "users"),
                List.of(key(null, "app", "users", "id", null, "app", "orders", "user_id", "fk_user")),
                List.of(unique(null, "app", "orders", "user_id", "tenant_id")));

        assertEquals(ErDiagramGraph.Cardinality.ONE_TO_MANY, graph.relations().get(0).cardinality());
    }

    @Test
    void missingUniqueKeysStayOneToMany() {
        ErDiagramGraph.AssembleResult graph = ErDiagramGraph.assemble(
                schemaScope(null, "app"),
                local("orders", "users"),
                List.of(key(null, "app", "users", "id", null, "app", "orders", "user_id", "fk_user")),
                List.of());

        assertEquals(ErDiagramGraph.Cardinality.ONE_TO_MANY, graph.relations().get(0).cardinality());
    }

    @Test
    void uniqueKeyOnTheSameTableNameInAnotherSchemaDoesNotApply() {
        ErDiagramGraph.AssembleResult graph = ErDiagramGraph.assemble(
                schemaScope(null, "app"),
                local("users"),
                List.of(key(null, "audit", "users", "id", null, "app", "users", "parent_id", "fk_parent")),
                List.of(unique(null, "audit", "users", "id"), unique(null, "app", "users", "id")));

        assertEquals(ErDiagramGraph.Cardinality.ONE_TO_MANY, graph.relations().get(0).cardinality());
    }

    @Test
    void uniqueForeignKeyStaysOneToOneWhenTheParentIsExternal() {
        ErDiagramGraph.AssembleResult graph = ErDiagramGraph.assemble(
                schemaScope(null, "app"),
                local("profiles"),
                List.of(key(null, "dict", "users", "id", null, "app", "profiles", "user_id", "fk_user")),
                List.of(unique(null, "dict", "users", "id"), unique(null, "app", "profiles", "user_id")));

        assertEquals(ErDiagramGraph.Cardinality.ONE_TO_ONE, graph.relations().get(0).cardinality());
        assertTrue(graph.relations().get(0).to().external());
    }

    @Test
    void blankForeignKeyNamesStaySeparateRelations() {
        ErDiagramGraph.AssembleResult graph = ErDiagramGraph.assemble(
                schemaScope(null, "app"),
                local("orders", "users"),
                List.of(
                        key(null, "app", "users", "id", null, "app", "orders", "user_id", " "),
                        key(null, "app", "users", "tenant_id", null, "app", "orders", "tenant_id", null)));

        assertEquals(2, graph.relations().size());
        assertEquals("user_id", graph.relations().get(0).fromColumn());
        assertEquals("tenant_id", graph.relations().get(1).fromColumn());
    }

    @Test
    void differentForeignKeyNamesStaySeparateRelations() {
        ErDiagramGraph.AssembleResult graph = ErDiagramGraph.assemble(
                schemaScope(null, "app"),
                local("orders", "users"),
                List.of(
                        key(null, "app", "users", "id", null, "app", "orders", "user_id", "fk_user"),
                        key(null, "app", "users", "id", null, "app", "orders", "owner_id", "fk_owner")));

        assertEquals(2, graph.relations().size());
        assertEquals(ErDiagramGraph.Cardinality.ONE_TO_MANY, graph.relations().get(0).cardinality());
        assertEquals(ErDiagramGraph.Cardinality.ONE_TO_MANY, graph.relations().get(1).cardinality());
    }

    @Test
    void foreignKeyTablesUseTheOpenedSchemaForLocalTablesAndKeepExternalSchemas() {
        List<ErDiagramGraph.TableRef> tables = ErDiagramGraph.foreignKeyTables(
                schemaScope(null, "app"),
                local("orders"),
                List.of(
                        key(null, "dict", "users", "id", null, "APP", "Orders", "user_id", "fk_user"),
                        key(null, "app", "orders", "id", null, "billing", "invoices", "order_id", "fk_order")));

        assertEquals(2, tables.size());
        assertEquals("orders", tables.get(0).table());
        assertEquals("app", tables.get(0).schema());
        assertEquals("invoices", tables.get(1).table());
        assertEquals("billing", tables.get(1).schema());
    }

    @Test
    void uniqueKeysFromIndexesKeepPrimaryAndUniqueIndexesOnly() {
        List<ErDiagramGraph.UniqueKey> keys = ErDiagramGraph.uniqueKeysFromIndexes(null, "app", "profiles", List.of(
                new IndexMetadata("uk_user", "BTREE", List.of("user_id"), true),
                new IndexMetadata("idx_name", "BTREE", List.of("name"), false),
                new IndexMetadata("PRIMARY", "BTREE", List.of("id"), true, true),
                new IndexMetadata("uk_empty", "BTREE", List.of(), true)));

        assertEquals(2, keys.size());
        assertEquals(List.of("user_id"), keys.get(0).columns());
        assertEquals(List.of("id"), keys.get(1).columns());
        assertEquals("profiles", keys.get(0).table());
        assertEquals("app", keys.get(0).schema());
    }

    @Test
    void junctionTableAddsManyToManyAndKeepsBothForeignKeys() {
        ErDiagramGraph.AssembleResult graph = junction(
                local("user_roles", "users", "roles"),
                List.of(
                        key(null, "app", "users", "id", null, "app", "user_roles", "user_id", "fk_user"),
                        key(null, "app", "roles", "id", null, "app", "user_roles", "role_id", "fk_role")),
                List.of(unique(null, "app", "user_roles", "user_id", "role_id")));

        assertEquals(2, physical(graph).size());
        ErDiagramGraph.Relation many = onlyManyToMany(graph);
        assertEquals("user_roles", many.via().table());
        assertEquals("app", many.via().schema());
        assertFalse(many.via().external());
        assertEquals(Set.of("users", "roles"), Set.of(many.from().table(), many.to().table()));
        assertEquals(List.of("id"), many.fromColumns());
        assertEquals(List.of("id"), many.toColumns());
    }

    @Test
    void payloadColumnsOutsideTheJunctionKeyStillFormManyToMany() {
        ErDiagramGraph.AssembleResult graph = junction(
                local("user_roles", "users", "roles"),
                List.of(
                        key(null, "app", "users", "id", null, "app", "user_roles", "user_id", "fk_user"),
                        key(null, "app", "roles", "id", null, "app", "user_roles", "role_id", "fk_role")),
                List.of(
                        unique(null, "app", "user_roles", "user_id", "role_id"),
                        unique(null, "app", "user_roles", "created_at")));

        assertEquals(1, manyToManyCount(graph));
        assertEquals("user_roles", onlyManyToMany(graph).via().table());
    }

    @Test
    void selfReferentialJunctionIsManyToMany() {
        ErDiagramGraph.AssembleResult graph = junction(
                local("friendships", "users"),
                List.of(
                        key(null, "app", "users", "id", null, "app", "friendships", "user_id", "fk_user"),
                        key(null, "app", "users", "id", null, "app", "friendships", "friend_id", "fk_friend")),
                List.of(unique(null, "app", "friendships", "user_id", "friend_id")));

        ErDiagramGraph.Relation many = onlyManyToMany(graph);
        assertEquals("friendships", many.via().table());
        assertEquals("users", many.from().table());
        assertEquals("users", many.to().table());
        assertEquals(2, physical(graph).size());
    }

    @Test
    void junctionWithOneExternalParentIsManyToMany() {
        ErDiagramGraph.AssembleResult graph = junction(
                local("user_roles", "users"),
                List.of(
                        key(null, "app", "users", "id", null, "app", "user_roles", "user_id", "fk_user"),
                        key(null, "dict", "roles", "id", null, "app", "user_roles", "role_id", "fk_role")),
                List.of(unique(null, "app", "user_roles", "User_Id", "Role_Id")));

        ErDiagramGraph.Relation many = onlyManyToMany(graph);
        assertEquals("user_roles", many.via().table());
        assertFalse(many.via().external());
        long externalEnds = List.of(many.from(), many.to()).stream().filter(ErDiagramGraph.Endpoint::external).count();
        assertEquals(1, externalEnds);
        assertEquals(Set.of("users", "roles"), Set.of(many.from().table(), many.to().table()));
    }

    @Test
    void junctionWithBothParentsExternalIsManyToMany() {
        ErDiagramGraph.AssembleResult graph = junction(
                local("user_roles"),
                List.of(
                        key(null, "dict", "users", "id", null, "app", "user_roles", "user_id", "fk_user"),
                        key(null, "billing", "roles", "id", null, "app", "user_roles", "role_id", "fk_role")),
                List.of(unique(null, "app", "user_roles", "user_id", "role_id")));

        ErDiagramGraph.Relation many = onlyManyToMany(graph);
        assertTrue(many.from().external());
        assertTrue(many.to().external());
        assertFalse(many.via().external());
    }

    @Test
    void entityWithItsOwnPrimaryKeyStaysTwoOneToManyRelations() {
        ErDiagramGraph.AssembleResult graph = junction(
                local("orders", "users", "products"),
                List.of(
                        key(null, "app", "users", "id", null, "app", "orders", "user_id", "fk_user"),
                        key(null, "app", "products", "id", null, "app", "orders", "product_id", "fk_product")),
                List.of(unique(null, "app", "orders", "id")));

        assertEquals(0, manyToManyCount(graph));
        assertEquals(2, graph.relations().size());
        assertEquals(ErDiagramGraph.Cardinality.ONE_TO_MANY, graph.relations().get(0).cardinality());
        assertEquals(ErDiagramGraph.Cardinality.ONE_TO_MANY, graph.relations().get(1).cardinality());
    }

    @Test
    void uniqueKeyCoveringBothForeignKeysIsManyToMany() {
        ErDiagramGraph.AssembleResult graph = junction(
                local("user_roles", "users", "roles"),
                List.of(
                        key(null, "app", "users", "id", null, "app", "user_roles", "user_id", "fk_user"),
                        key(null, "app", "roles", "id", null, "app", "user_roles", "role_id", "fk_role")),
                List.of(
                        unique(null, "app", "user_roles", "id"),
                        unique(null, "app", "user_roles", "user_id", "role_id")));

        assertEquals(1, manyToManyCount(graph));
        assertEquals(2, physical(graph).size());
    }

    @Test
    void threeForeignKeysStaySeparateRelations() {
        ErDiagramGraph.AssembleResult graph = junction(
                local("assignments", "users", "roles", "orgs"),
                List.of(
                        key(null, "app", "users", "id", null, "app", "assignments", "user_id", "fk_user"),
                        key(null, "app", "roles", "id", null, "app", "assignments", "role_id", "fk_role"),
                        key(null, "app", "orgs", "id", null, "app", "assignments", "org_id", "fk_org")),
                List.of(unique(null, "app", "assignments", "user_id", "role_id", "org_id")));

        assertEquals(0, manyToManyCount(graph));
        assertEquals(3, graph.relations().size());
    }

    @Test
    void overlappingForeignKeyColumnsAreNotManyToMany() {
        ErDiagramGraph.AssembleResult graph = junction(
                local("memberships", "users", "orgs"),
                List.of(
                        key(null, "app", "users", "id", null, "app", "memberships", "user_id", "fk_user", 1),
                        key(null, "app", "users", "tenant_id", null, "app", "memberships", "tenant_id", "fk_user", 2),
                        key(null, "app", "orgs", "tenant_id", null, "app", "memberships", "tenant_id", "fk_org", 1),
                        key(null, "app", "orgs", "id", null, "app", "memberships", "org_id", "fk_org", 2)),
                List.of(unique(null, "app", "memberships", "user_id", "tenant_id", "org_id")));

        assertEquals(0, manyToManyCount(graph));
        assertEquals(2, graph.relations().size());
    }

    @Test
    void externalJunctionIsNotExpandedIntoManyToMany() {
        ErDiagramGraph.AssembleResult graph = junction(
                local("users"),
                List.of(
                        key(null, "app", "users", "id", null, "billing", "user_roles", "user_id", "fk_user"),
                        key(null, "dict", "roles", "id", null, "billing", "user_roles", "role_id", "fk_role")),
                List.of(unique(null, "billing", "user_roles", "user_id", "role_id")));

        assertEquals(0, manyToManyCount(graph));
        assertEquals(2, graph.relations().size());
        assertTrue(graph.relations().get(0).from().external());
        assertTrue(graph.relations().get(1).from().external());
    }

    private static ErDiagramGraph.AssembleResult junction(Map<String, String> tables, List<ErDiagramGraph.KeyRow> keys,
                                                          List<ErDiagramGraph.UniqueKey> uniqueKeys) {
        return ErDiagramGraph.assemble(schemaScope(null, "app"), tables, keys, uniqueKeys);
    }

    private static List<ErDiagramGraph.Relation> physical(ErDiagramGraph.AssembleResult graph) {
        return graph.relations().stream()
                .filter(relation -> relation.cardinality() != ErDiagramGraph.Cardinality.MANY_TO_MANY)
                .toList();
    }

    private static long manyToManyCount(ErDiagramGraph.AssembleResult graph) {
        return graph.relations().stream()
                .filter(relation -> relation.cardinality() == ErDiagramGraph.Cardinality.MANY_TO_MANY)
                .count();
    }

    private static ErDiagramGraph.Relation onlyManyToMany(ErDiagramGraph.AssembleResult graph) {
        List<ErDiagramGraph.Relation> found = graph.relations().stream()
                .filter(relation -> relation.cardinality() == ErDiagramGraph.Cardinality.MANY_TO_MANY)
                .toList();
        assertEquals(1, found.size());
        return found.get(0);
    }

    private static ConstraintMetadata constraint(String name, String type, List<String> columns,
                                                 String referencedSchema, String referencedTable,
                                                 List<String> referencedColumns) {
        return new ConstraintMetadata(name, "orders", type, columns, referencedSchema, referencedTable,
                referencedColumns, null, true);
    }

    private static ErDiagramGraph.Scope schemaScope(String catalog, String schema) {
        return new ErDiagramGraph.Scope(catalog, schema, true);
    }

    private static Map<String, String> local(String... names) {
        Map<String, String> local = new LinkedHashMap<>();
        for (String name : names) {
            local.put(name.toLowerCase(), name);
        }
        return local;
    }

    private static ErDiagramGraph.KeyRow key(String pkCatalog, String pkSchema, String pkTable, String pkColumn,
                                             String fkCatalog, String fkSchema, String fkTable, String fkColumn,
                                             String name) {
        return key(pkCatalog, pkSchema, pkTable, pkColumn, fkCatalog, fkSchema, fkTable, fkColumn, name, 0);
    }

    private static ErDiagramGraph.KeyRow key(String pkCatalog, String pkSchema, String pkTable, String pkColumn,
                                             String fkCatalog, String fkSchema, String fkTable, String fkColumn,
                                             String name, int keySeq) {
        return new ErDiagramGraph.KeyRow(pkCatalog, pkSchema, pkTable, pkColumn, fkCatalog, fkSchema, fkTable, fkColumn, name, keySeq);
    }

    private static ErDiagramGraph.UniqueKey unique(String catalog, String schema, String table, String... columns) {
        return new ErDiagramGraph.UniqueKey(catalog, schema, table, List.of(columns));
    }
}
