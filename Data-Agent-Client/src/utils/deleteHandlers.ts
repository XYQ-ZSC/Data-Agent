import { ExplorerNodeType } from '../constants/explorer';
import { tableService } from '../services/table.service';
import { viewService } from '../services/view.service';
import { functionService } from '../services/function.service';
import { procedureService } from '../services/procedure.service';
import { triggerService } from '../services/trigger.service';
import { sequenceService } from '../services/sequence.service';
import { constraintService } from '../services/constraint.service';
import { databaseService } from '../services/database.service';

export interface DeleteParams {
  connectionId: string;
  name: string;
  catalog?: string;
  schema?: string;
  tableName?: string;
}

type DeleteServiceFn = (params: DeleteParams) => Promise<void>;

const DELETE_SERVICE_REGISTRY: Partial<Record<ExplorerNodeType, DeleteServiceFn>> = {
  [ExplorerNodeType.TABLE]: ({ connectionId, name, catalog, schema }) =>
    tableService.deleteTable(connectionId, name, catalog, schema),

  [ExplorerNodeType.VIEW]: ({ connectionId, name, catalog, schema }) =>
    viewService.deleteView(connectionId, name, catalog, schema),

  [ExplorerNodeType.FUNCTION]: ({ connectionId, name, catalog, schema }) =>
    functionService.deleteFunction(connectionId, name, catalog, schema),

  [ExplorerNodeType.PROCEDURE]: ({ connectionId, name, catalog, schema }) =>
    procedureService.deleteProcedure(connectionId, name, catalog, schema),

  [ExplorerNodeType.TRIGGER]: ({ connectionId, name, catalog, schema }) =>
    triggerService.deleteTrigger(connectionId, name, catalog, schema),

  [ExplorerNodeType.SEQUENCE]: ({ connectionId, name, catalog, schema }) =>
    sequenceService.deleteSequence(connectionId, name, catalog, schema),

  [ExplorerNodeType.CONSTRAINT]: ({ connectionId, name, catalog, schema, tableName }) => {
    if (!tableName) throw new Error('Constraint table is required');
    return constraintService.deleteConstraint(connectionId, tableName, name, catalog, schema);
  },

  [ExplorerNodeType.DB]: ({ connectionId, name }) =>
    databaseService.deleteDatabase(connectionId, name),
};

export function getDeleteService(type: ExplorerNodeType): DeleteServiceFn | undefined {
  return DELETE_SERVICE_REGISTRY[type];
}
