# 实战案例：表重命名功能开发（前后端全栈）

## 一、需求分析

### 1.1 需求描述

在左侧数据库资源树中，右键点击某个**表节点**，弹出菜单中新增【重命名表】选项。点击后弹出对话框，输入新表名并确认，调用后端接口完成重命名，成功后树节点立即显示新名称。

### 1.2 效果预览

```
表节点（如 Team）
   │ 右键
   ▼
┌─────────────────┐
│  新建            │
│  查看 DDL        │
│  查看数据        │
│  重命名表   ← 新增 │      ┌──────────────────────────┐
│  删除表          │  →   │  重命名表                  │
└─────────────────┘      │  为表 "Team" 输入新名称。   │
                          │  ┌──────────────────────┐  │
                          │  │ Team                 │  │
                          │  └──────────────────────┘  │
                          │        [取消]  [重命名表]   │
                          └──────────────────────────┘
```

### 1.3 实现思路

本项目已有【删除表】功能，它与重命名**只有最后一行 SQL 不同**，其余链路完全一样。因此本案例的开发策略是：

> **以"删除表"为参照物，逐层对照，照猫画虎。**

整体调用链如下（建议讲解时画在黑板上）：

```
【前端 Data-Agent-Client】
NodeContextMenuContent（右键菜单）
   └─> useRenameActions.confirmRename（动作处理）
        └─> tableService.renameTable（发 HTTP 请求）
             └─> PUT /api/tables/rename  ──────────┐
                                                    │
【服务端 Data-Agent-Server】                         │
TableController.renameTable（接收请求）        ◄──────┘
   └─> TableServiceImpl.renameTable（开连接、找插件）
        └─> TableManager.renameTable（插件接口，默认"不支持"）
             └─> MysqlTableManager（MySQL 插件实现）
                  └─> MysqlCapabilitySupport.renameObject
                       └─> 执行 SQL：RENAME TABLE `db`.`旧名` TO `新名`

【前端收尾】
renameNodeById（就地更新树节点名称）→ 页面自动刷新显示
```

## 二、前置知识

### 2.1 服务端分层架构

| 分层 | 职责 | 本项目中的位置 |
|---|---|---|
| Controller 层 | 接收 HTTP 请求、参数校验 | `api/controller/db/TableController.java` |
| Service 层 | 业务逻辑、管理数据库连接 | `domain/service/db/impl/TableServiceImpl.java` |
| 插件层（SPI） | 不同数据库的差异化实现 | `plugin/capability/TableManager.java`（接口） |
| 插件实现 | MySQL 的具体 SQL 拼装与执行 | `mysql-plugin/.../MysqlTableManager.java` |

> **小贴士**：SPI（Service Provider Interface）机制是指"接口定义能力，各数据库插件按需实现"。MySQL 能做的事，Oracle 不一定能做，所以接口方法都有默认实现：抛出 `UnsupportedOperationException`（不支持）。

### 2.2 前端分层

| 分层 | 职责 | 本项目中的位置 |
|---|---|---|
| service 层 | 封装 HTTP 请求 | `services/table.service.ts` |
| hooks 层 | 状态与业务逻辑 | `hooks/useRenameActions.ts` |
| 组件层 | 界面渲染 | `components/explorer/*.tsx` |
| 工具层 | 纯函数工具 | `utils/treeOperations.ts` |

## 三、服务端开发

### 3.1 步骤 1：创建请求 DTO

**参照物**：`DeleteTableRequest.java`

在 `Data-Agent-Server/data-agent-server-app/src/main/java/edu/zsc/ai/domain/model/dto/request/db/` 目录下新建 `RenameTableRequest.java`：

```java
package edu.zsc.ai.domain.model.dto.request.db;

import edu.zsc.ai.api.model.request.BaseRequest;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class RenameTableRequest extends BaseRequest {

    @NotBlank(message = "tableName is required")
    private String tableName;

    @NotBlank(message = "newTableName is required")
    private String newTableName;
}
```

**代码讲解：**

1. DTO（Data Transfer Object）是"前端传给后端的数据包"，一个接口对应一个请求类。
2. 父类 `BaseRequest` 已经封装了 `connectionId`（连接 ID）、`catalog`（数据库名）、`schema`，所以这里只需要声明两个业务字段。
3. `@NotBlank` 是参数校验注解：字段为空时框架自动返回 400 错误，**不需要手写 if 判断**。
4. `@Data`、`@NoArgsConstructor` 等是 Lombok 注解，编译期自动生成 getter/setter/构造方法。

### 3.2 步骤 2：Controller 添加接口

**参照物**：`TableController.deleteTable()`

打开 `TableController.java`，在 `deleteTable` 方法下方添加：

```java
@PutMapping("/rename")
public ApiResponse<Void> renameTable(@Valid @RequestBody RenameTableRequest request) {
    log.info("Renaming table: connectionId={}, tableName={}, newTableName={}, catalog={}, schema={}",
            request.getConnectionId(), request.getTableName(), request.getNewTableName(),
            request.getCatalog(), request.getSchema());
    DbContext db = DbContext.from(request);
    tableService.renameTable(db, request.getTableName(), request.getNewTableName());
    return ApiResponse.success(null);
}
```

同时在文件头部补充两个 import：

```java
import edu.zsc.ai.domain.model.dto.request.db.RenameTableRequest;
import org.springframework.web.bind.annotation.PutMapping;
```

**代码讲解：**

1. `@PutMapping("/rename")`：RESTful 风格。删表用 `DELETE /api/tables`（删除资源），改名是"修改资源属性"，所以用 `PUT /api/tables/rename`。
2. `@Valid`：触发 DTO 上的 `@NotBlank` 校验。
3. `DbContext.from(request)`：把请求里的连接信息打包成上下文对象，后续每一层都靠它找到正确的数据库连接。

### 3.3 步骤 3：Service 层

**参照物**：`TableService.deleteTable()` / `TableServiceImpl.deleteTable()`

先在接口 `TableService.java` 中声明方法：

```java
void renameTable(DbContext db, String tableName, String newTableName);
```

再在实现类 `TableServiceImpl.java` 中实现：

```java
@Override
public void renameTable(DbContext db, String tableName, String newTableName) {
    connectionService.openConnection(db);

    ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
    TableManager provider = DefaultPluginManager.getInstance().getTableManagerByPluginId(active.pluginId());
    try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
        provider.renameTable(borrowed.connection(), db.catalog(), db.schema(), tableName, newTableName);
    }

    log.info("Table renamed successfully: connectionId={}, catalog={}, schema={}, tableName={}, newTableName={}",
            db.connectionId(), db.catalog(), db.schema(), tableName, newTableName);
}
```

**代码讲解：**

这一层是固定四步套路，所有表操作都一样：

```
打开连接 → 找到当前连接对应的插件（MySQL/Oracle...）→ 借用连接执行 → 记录日志
```

> **注意**：`try (BorrowedConnection ...)` 使用了 try-with-resources 语法，执行完自动归还连接，防止连接泄漏。

### 3.4 步骤 4：插件接口（SPI）扩展

**参照物**：`TableManager.deleteTable()`

打开插件接口 `data-agent-server-plugin/.../capability/TableManager.java`，添加默认方法：

```java
default void renameTable(Connection connection, String catalog, String schema,
                         String tableName, String newTableName) {
    throw new UnsupportedOperationException("Plugin does not support renaming table");
}
```

**代码讲解：**

> 面试常考点来了——**为什么用 default 默认方法，而不是抽象方法？**
>
> 如果定义成抽象方法，那么所有实现类（MySQL 插件、Oracle 插件、PG 插件……）都必须立刻实现它，否则编译报错。使用默认方法后：老插件**一行都不用改**，调用时自动返回"不支持"。这就是"开闭原则"：**对扩展开放，对修改关闭**。

### 3.5 步骤 5：MySQL 插件实现

**3 个小改动，参照物都是"删表"。**

**① SQL 模板**——打开 `MySqlTemplate.java`，在 `SQL_DROP_TABLE` 旁添加：

```java
/** %1$s = old full table name, %2$s = new quoted table name */
public static final String SQL_RENAME_TABLE = "RENAME TABLE %s TO %s";
```

**② 通用执行逻辑**——打开 `MysqlCapabilitySupport.java`，参照 `dropObject()` 添加：

```java
public void renameObject(Connection connection, String catalog, String objectName, String newObjectName,
                         String sqlTemplate, String objectType) {
    if (connection == null || StringUtils.isBlank(objectName) || StringUtils.isBlank(newObjectName)) {
        throw new IllegalArgumentException(
                String.format("Connection, %s name and new name must not be null or empty", objectType));
    }

    String fullOldName = MysqlIdentifierBuilder.buildFullIdentifier(catalog, objectName);
    String quotedNewName = MysqlIdentifierEscaper.getInstance().quoteIdentifier(newObjectName.trim());
    String sql = String.format(sqlTemplate, fullOldName, quotedNewName);

    SqlCommandResult result = execute(connection, catalog, sql);
    if (!result.isSuccess()) {
        throw new RuntimeException(String.format("Failed to rename %s: %s", objectType, result.getErrorMessage()));
    }
}
```

**③ 插件入口**——打开 `MysqlTableManager.java`，参照 `deleteTable()` 添加：

```java
@Override
public void renameTable(Connection connection, String catalog, String schema,
                        String tableName, String newTableName) {
    support.renameObject(
            connection,
            catalog,
            tableName,
            newTableName,
            MySqlTemplate.SQL_RENAME_TABLE,
            DatabaseObjectTypeEnum.TABLE.getValue()
    );
}
```

**代码讲解：**

> **安全重点**：表名是用户输入的，拼 SQL 前必须经过 `quoteIdentifier()` 转义（给表名加反引号 `` ` `` 并转义特殊字符）。直接字符串拼接会导致 SQL 注入，这是后端开发的红线。

### 3.6 步骤 6：编译验证

```bash
cd Data-Agent-Server
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
mvn -q compile -DskipTests && echo "编译成功"
```

> **注意事项**：本机如果默认使用 JDK 25，会出现大量 `找不到符号 builder()` 的错误。这不是代码问题，而是 **Lombok 旧版本不兼容新 JDK**。项目 `pom.xml` 中配置的 `java.version` 是 17，所以编译前必须切换到 JDK 17。遇到编译报错时，先区分"代码写错了"还是"环境问题"。

**【Git 提交点 1】** 服务端是一个可独立验证的完整单元，编译通过后立即提交：

```bash
git add Data-Agent-Server
git commit -m "feat(server): support renaming table"
```

### 3.7 本节小结

服务端一共改了 **8 个文件**（7 改 1 增），每一处的参照物都是"删除表"：

| 文件 | 改动 | 参照物 |
|---|---|---|
| `RenameTableRequest.java` | 新增 DTO | `DeleteTableRequest` |
| `TableController.java` | 新增 PUT 接口 | `deleteTable()` |
| `TableService.java` | 接口加一行声明 | `deleteTable()` |
| `TableServiceImpl.java` | 实现方法 | `deleteTable()` |
| `TableManager.java` | SPI 默认方法 | `deleteTable()` |
| `MysqlTableManager.java` | MySQL 入口 | `deleteTable()` |
| `MysqlCapabilitySupport.java` | SQL 拼装执行 | `dropObject()` |
| `MySqlTemplate.java` | SQL 模板常量 | `SQL_DROP_TABLE` |

## 四、前端开发

### 4.1 步骤 1：注册 API 路径

**参照物**：`TABLES_DDL`

打开 `Data-Agent-Client/src/constants/apiPaths.ts`，添加：

```ts
TABLES_RENAME: '/tables/rename',
```

### 4.2 步骤 2：封装 service 请求

**参照物**：`tableService.deleteTable()`

打开 `services/table.service.ts`，在 `deleteTable` 后添加：

```ts
renameTable: async (
  connectionId: string,
  tableName: string,
  newTableName: string,
  catalog?: string,
  schema?: string
): Promise<void> => {
  await http.put(ApiPaths.TABLES_RENAME, {
    connectionId,
    tableName,
    newTableName,
    catalog,
    schema
  });
},
```

**代码讲解：** service 层只做一件事——把参数打包发给后端。`http.put` 对应后端的 `@PutMapping`。

### 4.3 步骤 3：树节点改名工具函数

**参照物**：`removeNodeById()`（删除节点）

打开 `utils/treeOperations.ts`，添加：

```ts
/**
 * Renames a node in the tree by ID.
 * Returns a new array with the node's display name (and objectName, when it
 * tracked the old name) updated.
 */
export function renameNodeById(nodes: ExplorerNode[], nodeId: string, newName: string): ExplorerNode[] {
  return nodes.map((node) => {
    if (node.id === nodeId) {
      return {
        ...node,
        name: newName,
        objectName: node.objectName != null ? newName : node.objectName,
      };
    }
    if (node.children && node.children.length > 0) {
      return { ...node, children: renameNodeById(node.children, nodeId, newName) };
    }
    return node;
  });
}
```

**代码讲解：**

> **React 新手最容易踩的坑**：这里必须返回**新数组 + 新对象**（不可变更新），而不能直接 `node.name = newName`。
>
> 原因：React 通过**比较对象引用是否变化**来决定要不要重新渲染。原地修改对象，引用没变，React 会认为"数据没变化"，界面就不会更新。`...node`（展开运算符）会创建一个新对象。

### 4.4 步骤 4：对话框状态管理

**参照物**：`deleteState`

打开 `hooks/useDialogState.ts`，先定义状态类型：

```ts
interface RenameState {
  node: ExplorerNode | null;   // 要重命名的节点
  isOpen: boolean;             // 对话框是否打开
  isPending: boolean;          // 是否正在提交（防止重复点击）
}
```

再在 hook 中添加状态和导出（参照 `deleteState` 的写法）：

```ts
// Rename table dialog
const [renameState, setRenameState] = useState<RenameState>({
  node: null,
  isOpen: false,
  isPending: false,
});

// 在 return 对象中导出
renameState,
setRenameState,
```

### 4.5 步骤 5：动作处理 hook

**参照物**：`useDeleteActions.ts`

新建 `hooks/useRenameActions.ts`，完整代码如下：

```ts
import { useCallback } from 'react';
import type React from 'react';
import { useTranslation } from 'react-i18next';
import { I18N_KEYS } from '../constants/i18nKeys';
import { useToast } from './useToast';
import { tableService } from '../services/table.service';
import { renameNodeById } from '../utils/treeOperations';
import type { ExplorerNode } from '../types/explorer';

interface RenameState {
  node: ExplorerNode | null;
  isOpen: boolean;
  isPending: boolean;
}

interface RenameActionsProps {
  setRenameState: React.Dispatch<React.SetStateAction<RenameState>>;
  renameState: RenameState;
  setTreeDataState: (cb: (prev: ExplorerNode[]) => ExplorerNode[]) => void;
}

export function useRenameActions({
  setRenameState,
  renameState,
  setTreeDataState,
}: RenameActionsProps) {
  const { t } = useTranslation();
  const toast = useToast();

  // 第一步：点击右键菜单，打开对话框
  const handleRename = useCallback((node: ExplorerNode) => {
    if (!node.connectionId) return;
    setRenameState({ node, isOpen: true, isPending: false });
  }, [setRenameState]);

  // 第二步：点击对话框的确认按钮，执行重命名
  const confirmRename = useCallback(async (newName: string) => {
    const { node } = renameState;
    const trimmedName = newName.trim();
    // 名称为空、或没改动 → 直接关闭，不发请求
    if (!node || !trimmedName || trimmedName === node.name) {
      setRenameState({ node: null, isOpen: false, isPending: false });
      return;
    }

    setRenameState((prev) => ({ ...prev, isPending: true }));

    try {
      await tableService.renameTable(
        node.connectionId!,
        node.name,
        trimmedName,
        node.catalog,
        node.schema,
      );

      // 成功后只更新这一个节点，不刷新整棵树
      setTreeDataState((prev: ExplorerNode[]) => renameNodeById(prev, node.id, trimmedName));
      toast.success(t(I18N_KEYS.EXPLORER.RENAME_TABLE_SUCCESS));
    } catch (error) {
      console.error('Failed to rename table:', error);
      toast.error((error as Error).message || t(I18N_KEYS.EXPLORER.RENAME_TABLE_FAILED));
    } finally {
      setRenameState({ node: null, isOpen: false, isPending: false });
    }
  }, [renameState, setRenameState, setTreeDataState, t, toast]);

  return { handleRename, confirmRename };
}
```

**代码讲解：**

1. hook 把"打开对话框"和"确认执行"两个动作封装起来，组件只负责调用。
2. `isPending` 期间按钮禁用，防止用户连点导致重复请求。
3. `try / catch / finally`：成功 toast 提示并就地更新树；失败 toast 报错；无论成败 finally 中都关闭对话框。

### 4.6 步骤 6：重命名对话框组件

**参照物**：`DeleteEntityDialog.tsx`

新建 `components/explorer/RenameTableDialog.tsx`，完整代码如下：

```tsx
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../ui/Dialog';
import { Button } from '../ui/Button';
import { Input } from '../ui/Input';
import { I18N_KEYS } from '../../constants/i18nKeys';

interface RenameTableDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  tableName: string;
  onConfirm: (newName: string) => void;
  isPending: boolean;
}

export function RenameTableDialog({
  open,
  onOpenChange,
  tableName,
  onConfirm,
  isPending,
}: RenameTableDialogProps) {
  const { t } = useTranslation();
  const [newName, setNewName] = useState(tableName);

  // 每次打开对话框时，把输入框重置为当前表名
  useEffect(() => {
    if (open) {
      setNewName(tableName);
    }
  }, [open, tableName]);

  const trimmedName = newName.trim();
  const canConfirm = !isPending && trimmedName !== '' && trimmedName !== tableName;

  const handleConfirm = () => {
    if (!canConfirm) return;
    onConfirm(trimmedName);
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-[400px]">
        <DialogHeader>
          <DialogTitle>{t(I18N_KEYS.EXPLORER.RENAME_TABLE)}</DialogTitle>
          <DialogDescription>
            {t(I18N_KEYS.EXPLORER.RENAME_TABLE_PROMPT, { name: tableName })}
          </DialogDescription>
        </DialogHeader>
        <Input
          autoFocus
          value={newName}
          disabled={isPending}
          onChange={(e) => setNewName(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              handleConfirm();
            }
          }}
        />
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={isPending}>
            {t(I18N_KEYS.CONNECTIONS.CANCEL)}
          </Button>
          <Button disabled={!canConfirm} onClick={handleConfirm}>
            {isPending ? t(I18N_KEYS.COMMON.LOADING) + '...' : t(I18N_KEYS.EXPLORER.RENAME_TABLE)}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
```

**代码讲解：**

1. `useEffect` 监听 `open`：每次打开都重置输入框，否则上次输入的内容会残留。
2. `canConfirm`：空名称、名称没变化、请求中，三种情况确认按钮置灰。
3. `onKeyDown` 监听回车：输入框里按 Enter 直接提交，提升体验。

### 4.7 步骤 7：右键菜单添加入口

**参照物**："删除表"菜单项

打开 `components/explorer/NodeContextMenuContent.tsx`，做 3 处修改：

**① 头部引入铅笔图标：**

```ts
import { FileText, Pencil, Plus, Table, Table2, Trash2 } from 'lucide-react';
```

**② Props 接口和解构中各加一行：**

```ts
interface NodeContextMenuContentProps {
  // ...其他不变
  onRename: (node: ExplorerNode) => void;
  onDelete: (node: ExplorerNode, type: ExplorerNodeType) => void;
}
```

**③ 在删除项之前插入重命名菜单项：**

```tsx
{/* Rename - for tables */}
{node.type === ExplorerNodeType.TABLE && (
  <>
    <ContextMenuItem onSelect={() => onRename(node)}>
      <Pencil className="w-3.5 h-3.5 mr-2" />
      {t(I18N_KEYS.EXPLORER.RENAME_TABLE)}
    </ContextMenuItem>
    <ContextMenuSeparator />
  </>
)}
```

### 4.8 步骤 8：props 逐层传递

`onRename` 回调要从最外层的容器组件，一路传到右键菜单，中间经过 3 层组件：

```
DatabaseExplorer（在这里创建 handleRename）
   └─> ExplorerTree（透传）
        └─> ExplorerTreeNode（透传）
             └─> NodeContextMenuContent（最终使用）
```

每层组件的改法完全一样，都是两行：**Props 接口加声明 + JSX 里加传递**。以 `ExplorerTree.tsx` 为例：

```tsx
// ① 接口声明
onRename: (node: ExplorerNode) => void;

// ② 解构 + 传递
<ExplorerTreeNode
  // ...其他 props
  onRename={onRename}
/>
```

> **知识点讲解**：这种一层层手工传递 props 的做法叫 **Prop Drilling（属性钻取）**。层级少时可以接受；如果未来穿透的层级变多，就应该考虑用 Context 或状态管理库来优化。架构方案没有绝对的好坏，只有是否匹配当前的规模。

最后在容器组件 `DatabaseExplorer.tsx` 中接入（参照 `useDeleteActions` 的用法）：

```tsx
// ① 引入 hook
import { useRenameActions } from '../../hooks/useRenameActions';

// ② 从 dialogState 解构出重命名状态
const { /* ... */, renameState, setRenameState } = dialogState;

// ③ 创建动作
const { handleRename, confirmRename } = useRenameActions({
  setRenameState,
  renameState,
  setTreeDataState,
});

// ④ 传给树组件
<ExplorerTree
  // ...其他 props
  onRename={handleRename}
/>

// ⑤ 传给对话框容器
<ExplorerDialogs
  // ...其他 props
  renameState={renameState}
  onRenameStateChange={setRenameState}
  onConfirmRename={confirmRename}
/>
```

### 4.9 步骤 9：挂载对话框

打开 `components/explorer/ExplorerDialogs.tsx`，在删除对话框之前添加：

```tsx
{renameState.node && (
  <RenameTableDialog
    open={renameState.isOpen}
    onOpenChange={(open) => {
      if (!open) {
        onRenameStateChange({ node: null, isOpen: false, isPending: false });
      }
    }}
    tableName={renameState.node.name}
    onConfirm={onConfirmRename}
    isPending={renameState.isPending}
  />
)}
```

（别忘了同步在 Props 接口中声明 `renameState / onRenameStateChange / onConfirmRename` 三个属性。）

### 4.10 步骤 10：国际化文案

本项目所有界面文字都走 i18n，**三个文件必须一起改**：

**① `constants/i18nKeys.ts`**（注册 key）：

```ts
RENAME_TABLE: 'explorer.rename_table',
RENAME_TABLE_PROMPT: 'explorer.rename_table_prompt',
RENAME_TABLE_SUCCESS: 'explorer.rename_table_success',
RENAME_TABLE_FAILED: 'explorer.rename_table_failed',
```

**② `i18n/locales/zh.json`**（中文）：

```json
"rename_table": "重命名表",
"rename_table_prompt": "为表 \"{{name}}\" 输入新名称。",
"rename_table_success": "重命名成功",
"rename_table_failed": "重命名失败",
```

**③ `i18n/locales/en.json`**（英文）：

```json
"rename_table": "Rename Table",
"rename_table_prompt": "Enter a new name for table \"{{name}}\".",
"rename_table_success": "Rename successful",
"rename_table_failed": "Rename failed",
```

> **知识点讲解**：`{{name}}` 是 i18next 框架的插值占位符，代码中 `t(key, { name: tableName })` 会把真实表名填进去。

### 4.11 步骤 11：前端验证

```bash
cd Data-Agent-Client
npx tsc --noEmit      # TypeScript 类型检查
npx eslint src/components/explorer/ src/hooks/   # 代码规范检查
```

全部通过后，启动前后端实际点一遍：右键表 → 重命名 → 输入新名 → 确认 → 树节点名称立即更新。

**【Git 提交点 2】**

```bash
git add Data-Agent-Client
git commit -m "feat(client): add rename table context menu action"
```

### 4.12 本节小结

前端一共改了 **15 个文件**（12 改 3 增），改动链路与删除表完全同构：

| 模块 | 文件 | 参照物 |
|---|---|---|
| 请求 | `apiPaths.ts` / `table.service.ts` | `deleteTable()` |
| 树操作 | `treeOperations.ts` | `removeNodeById()` |
| 状态 | `useDialogState.ts` | `deleteState` |
| 逻辑 | `useRenameActions.ts`（新增） | `useDeleteActions.ts` |
| 界面 | `RenameTableDialog.tsx`（新增） | `DeleteEntityDialog.tsx` |
| 菜单 | `NodeContextMenuContent.tsx` | "删除表"菜单项 |
| 传参 | `ExplorerTreeNode / ExplorerTree / DatabaseExplorer` | `onDelete` 的传递路径 |
| 挂载 | `ExplorerDialogs.tsx` | `DeleteEntityDialog` 的挂载方式 |
| 文案 | `i18nKeys.ts` + `zh.json` + `en.json` | 删除相关文案 |

## 五、Git 版本管理

### 5.1 提交信息规范（Conventional Commits）

![image-20260917150714784](/Users/huanghaizhi/Library/Application Support/typora-user-images/image-20260917150714784.png)

本仓库的提交记录：

```
5eb003da feat(server): add thinking tool
6a4b8acd fix(server): search schema-less databases with wildcard
d12e89cf fix(client): fold thinking tool runs
```

格式：`类型(范围): 描述`

- **类型**：`feat`（新功能）、`fix`（修 bug）、`refactor`（重构）、`docs`（文档）
- **范围**：`server` / `client`
- **描述**：英文小写、动词开头、不超过 50 字

### 5.2 为什么一个需求要拆成两个 commit？

本案例拆成了 `feat(server)` 和 `feat(client)` 两个提交，好处有三个：

1. **Code Review 友好**：审后端的人不用看前端代码；
2. **可独立回滚**：前端出问题可以 `git revert` 只回滚客户端提交；
3. **便于二分定位**：`git bisect` 能更精确地找到引入 bug 的提交。

> **提交粒度原则**：一个 commit = 一个可独立编译、可独立验证的完整单元。

### 5.3 git stash：临时收起半成品

本次开发中真实发生的场景："行编辑"功能做完了（17 个文件）但暂时不想提交，又需要干净的工作区去做别的小功能。

```bash
# 只暂存指定文件（-u 包含新文件，-- 后面跟文件路径）
git stash push -u -m "行修改" -- Data-Agent-Server/.../TableController.java ...

# 查看暂存列表
git stash list
# stash@{0}: On feat/desktop-offline: 行修改

# 恢复（两种方式）
git stash pop              # 恢复并删除这条 stash
git stash apply stash@{0}  # 恢复但保留 stash 记录
```

### 5.4 日常自查三板斧

```bash
git status --short   # 改了哪些文件（M=修改，??=新增）
git diff --stat      # 快速评估改动量
git log --oneline -5 # 回顾最近提交
```

### 5.5 常用 Git 命令速查

按"一天的工作流"顺序讲解，覆盖 90% 的日常场景。

**1）开始干活：拿到最新代码**

```bash
git clone <仓库地址>     # 第一次：把远程仓库完整下载到本地
git pull                 # 之后每天：拉取远程最新提交并合并到当前分支
git checkout -b feat/xxx # 从当前分支切出新分支，需求/bug 各开一个
```

**2）干活中：暂存与提交**

```bash
git add <文件>           # 把指定文件放进暂存区（准备提交）
git add -p               # 交互式逐块选择，只提交想提交的部分
git commit -m "feat(server): add rename api"  # 提交，信息遵循 5.1 的规范
git commit --amend       # 改上一次提交（补个漏掉的文件、改提交信息）
```

**3）看状态：随时知道自己在哪**

```bash
git status               # 工作区 / 暂存区整体状态
git diff                 # 还没 add 的改动
git diff --staged        # 已经 add、还没 commit 的改动
git log --oneline --graph -10  # 图形化看最近提交和分支关系
```

**4）分支操作**

```bash
git branch               # 看本地有哪些分支（* 是当前所在分支）
git checkout main        # 切换到 main 分支
git merge feat/xxx       # 把 feat/xxx 的改动合并进当前分支
git branch -d feat/xxx   # 删除已合并的分支（-D 强制删除）
```

**5）后悔药：撤销与回滚**

```bash
git restore <文件>           # 丢弃工作区的修改（还没 add 的）
git restore --staged <文件>  # 把文件从暂存区移回工作区（撤销 add）
git revert <commit>          # 生成一个"反向提交"来撤销历史提交，安全、可推送
git reset --hard HEAD~1      # 彻底删掉最近一次本地提交（危险！已推送的别用）
```

> **revert 和 reset 的区别**：`revert` 是"再做一次反操作"，历史记录保留，适合已推送的提交；`reset --hard` 是"时光倒流"，直接抹掉历史，只用于还没推送的本地提交。

**6）与远程同步**

```bash
git push                 # 把本地提交推送到远程同名分支
git push -u origin feat/xxx  # 第一次推送新分支，-u 建立跟踪关系
git fetch                # 只下载远程最新状态，不动本地代码（先看再合）
```

## 六、总结

### 6.1 开发流程回顾

```
需求分析 → 找参照物 → 画调用链 → 服务端实现 → 编译验证 → 提交
        → 前端实现 → 类型检查 → 实际点一遍 → 提交
```

### 6.2 本案例涉及的三个设计原则

| 原则 | 体现 |
|---|---|
| 开闭原则 | 插件接口用 default 方法扩展能力，其他数据库插件零改动 |
| 不可变数据 | 树节点更新返回新数组，React 才能感知变化 |
| 最小修改原则 | 23 个文件 +198 行，几乎全是纯新增，不动既有逻辑 |

### 6.3 方法论一句话

> **真实工作中的新需求开发，80% 是"找到最相似的旧功能，照着它的路再走一遍"。** 模式越统一的代码库，新需求的成本越低——这也是评价代码质量的重要标准。

## 七、课后作业

1. **【必做】** 参照本案例，给"视图（View）"节点也加上重命名功能。提示：服务端参照 `ViewController`；MySQL 中视图也可以用 `RENAME TABLE` 语句。
2. **【必做】** 把作业 1 的改动按"服务端 / 前端"拆成两个规范的 commit；然后故意引入一个 bug，练习使用 `git revert` 只回滚前端提交。
3. **【选做】** 思考：如果重命名一张表时，它的数据 Tab 正处于打开状态，会发生什么？应该怎么处理？（提示：看看 Tab 的 metadata 里存的是什么）
4. **【选做】** 思考：MySQL 支持 `RENAME TABLE db1.t TO db2.t` 跨库改名。如果要支持这个功能，界面和安全校验上分别需要做什么？
