package edu.zsc.ai.domain.service.db;

import java.util.List;

public interface DatabaseService {

    List<String> getDatabases(Long connectionId);

    void deleteDatabase(Long connectionId, String databaseName);
}
