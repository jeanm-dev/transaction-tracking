package com.training.java.transaction.tracker.repository;

import com.training.java.transaction.tracker.data.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

public class RepositoryBase<T, D extends TableDescriptor<T>> implements Repository<T> {

  private final Database database;
  private final D tableDescriptor;

  private static final String ADD_STATEMENT = "INSERT INTO {TABLE_NAME} ({ALL_COLUMNS}) VALUES ({VALUES_STRING});";
  private static final String SELECT_WHERE_STATEMENT = "SELECT {ALL_COLUMNS} FROM {TABLE_NAME} WHERE {ID_COLUMN} = ?;";
  private static final String DELETE_STATEMENT = "DELETE FROM {TABLE_NAME} WHERE {ID_COLUMN} = ?;";
  private static final String UPDATE_STATEMENT = "UPDATE {TABLE_NAME} SET {COLUMNS_TO_UPDATE} WHERE {ID_COLUMN} = ?;";
  private static final String SELECT_ALL_STATEMENT = "SELECT {ALL_COLUMNS} FROM {TABLE_NAME};";

  public RepositoryBase(Database database, D tableDescriptor) {
    this.database = database;
    this.tableDescriptor = tableDescriptor;
  }

  private String getInsertStatement() {
    String columnNames = String.join(",", tableDescriptor.getColumnNames());
    String values = tableDescriptor.getColumnNames().stream().map(l -> "?")
        .collect(Collectors.joining(","));

    return ADD_STATEMENT
        .replace("{TABLE_NAME}", tableDescriptor.getTableName())
        .replace("{ALL_COLUMNS}", columnNames)
        .replace("{VALUES_STRING}", values);
  }

  private String getWhereClause() {
    String columnNames = tableDescriptor.getIdentifierColumnName() + "," + String
        .join(",", tableDescriptor.getColumnNames());

    return SELECT_WHERE_STATEMENT
        .replace("{TABLE_NAME}", tableDescriptor.getTableName())
        .replace("{ALL_COLUMNS}", columnNames)
        .replace("{ID_COLUMN}", tableDescriptor.getIdentifierColumnName());
  }

  private String getDeleteStatement() {
    return DELETE_STATEMENT
        .replace("{TABLE_NAME}", tableDescriptor.getTableName())
        .replace("{ID_COLUMN}", tableDescriptor.getIdentifierColumnName());
  }

  private String getUpdateStatement() {
    String columnsToUpdate = String.join(" = ?, ", tableDescriptor.getColumnNames()) + " = ? ";
    return UPDATE_STATEMENT
        .replace("{TABLE_NAME}", tableDescriptor.getTableName())
        .replace("{COLUMNS_TO_UPDATE}", columnsToUpdate)
        .replace("{ID_COLUMN}", tableDescriptor.getIdentifierColumnName());
  }

  private String getSelectAllStatement() {
    String columnNames = tableDescriptor.getIdentifierColumnName() + "," + String
        .join(",", tableDescriptor.getColumnNames());
    return SELECT_ALL_STATEMENT
        .replace("{TABLE_NAME}", tableDescriptor.getTableName())
        .replace("{ALL_COLUMNS}", columnNames);
  }

  @Override
  public T create(T object) throws Exception {
    Connection connection = database.getConnection();

    PreparedStatement preparedStatement = connection
        .prepareStatement(getInsertStatement(), Statement.RETURN_GENERATED_KEYS);

    for (int i = 0; i < tableDescriptor.getColumnNames().size(); i++) {
      String columnName = tableDescriptor.getColumnNames().get(i);

      Object insertObject = tableDescriptor.getColumnValueMappers().get(columnName).apply(object);
      if (insertObject != null) {
        preparedStatement.setObject(i + 1, insertObject);
      } else if (tableDescriptor.getRequiredColumnNames().get(columnName)) {
        throw new MissingFieldValueException(columnName);
      } else {
        preparedStatement.setObject(i + 1, null);
      }
    }
    preparedStatement.execute();

    ResultSet resultSet = preparedStatement.getGeneratedKeys();
    if (resultSet.next()) {
      long identifier = resultSet.getLong(1);
      tableDescriptor.getIdentifierSetter().accept(object, identifier);
    }

    preparedStatement.close();

    return object;
  }

  @Override
  public boolean doesIdExist(long id) throws SQLException {
    Connection connection = database.getConnection();

    PreparedStatement preparedStatement = connection.prepareStatement(getWhereClause());
    preparedStatement.setLong(1, id);

    System.out.println(preparedStatement);
    ResultSet resultSet = preparedStatement.executeQuery();

    while (resultSet.next()) {
      return true;
    }

    preparedStatement.close();

    return false;
  }

  @Override
  public T fetchById(long id) throws SQLException {
    Connection connection = database.getConnection();

    PreparedStatement preparedStatement = connection.prepareStatement(getWhereClause());
    preparedStatement.setLong(1, id);

    System.out.println(preparedStatement);
    ResultSet resultSet = preparedStatement.executeQuery();

    if (resultSet.next()) {
      T newObject = tableDescriptor.newObject();
      // Setup identifier
      long identifier = resultSet.getLong(1);
      tableDescriptor.getIdentifierSetter().accept(newObject, identifier);

      Map<String, BiConsumer<T, Object>> columnSetters = tableDescriptor.getColumnSetters();
      for (String columnName : tableDescriptor.getColumnNames()) {
        Object value = resultSet.getObject(columnName);
        columnSetters.get(columnName).accept(newObject, value);
      }

      preparedStatement.close();
      return newObject;
    }

    preparedStatement.close();
    return null;
  }

  @Override
  public boolean remove(long id) throws SQLException {
    Connection connection = database.getConnection();

    PreparedStatement preparedStatement = connection.prepareStatement(getDeleteStatement());
    preparedStatement.setLong(1, id);

    preparedStatement.execute();

    // Determine if changes where applied
    int updateCount = preparedStatement.getUpdateCount();

    preparedStatement.close();

    return updateCount > 0;
  }

  @Override
  public void update(T object) throws Exception {
    if (tableDescriptor.getIdentifierExtractor().apply(object) == null) {
      throw new MissingIdentifierValueException();
    }

    Connection connection = database.getConnection();
    PreparedStatement preparedStatement = connection.prepareStatement(getUpdateStatement());

    System.out.println(getUpdateStatement());

    int i;
    for (i = 0; i < tableDescriptor.getColumnNames().size(); i++) {
      String columnName = tableDescriptor.getColumnNames().get(i);
      int columnIndex = i + 1;
      preparedStatement
          .setObject(columnIndex,
              tableDescriptor.getColumnValueMappers().get(columnName).apply(object));
    }

    int identifierIndex = i + 1;
    preparedStatement
        .setLong(identifierIndex, tableDescriptor.getIdentifierExtractor().apply(object));

    System.out.println(preparedStatement);
    preparedStatement.execute();
    preparedStatement.close();
  }

  @Override
  public List<T> fetchAll() throws SQLException {
    List<T> objectList = new ArrayList<>();

    Connection connection = database.getConnection();
    PreparedStatement preparedStatement = connection.prepareStatement(getSelectAllStatement());

    System.out.println(getSelectAllStatement());

    ResultSet resultSet = preparedStatement.executeQuery();

    while (resultSet.next()) {
      T newObject = tableDescriptor.newObject();
      // Setup identifier
      long identifier = resultSet.getLong(1);
      tableDescriptor.getIdentifierSetter().accept(newObject, identifier);

      Map<String, BiConsumer<T, Object>> columnSetters = tableDescriptor.getColumnSetters();
      for (String columnName : tableDescriptor.getColumnNames()) {
        Class<?> type = tableDescriptor.getColumnTypes().get(columnName);

        if (type.equals(Long.class)) {
          Long value = resultSet.getLong(columnName);
          columnSetters.get(columnName).accept(newObject, value);
        } else {
          Object value = resultSet.getObject(columnName);
          columnSetters.get(columnName).accept(newObject, value);
        }

      }

      objectList.add(newObject);
    }

    preparedStatement.close();

    return objectList;
  }

  @Override
  public boolean isValid(T object) {
    for (String columnName : tableDescriptor.getRequiredColumnNames().keySet()) {
      if (tableDescriptor.getColumnValueMappers().get(columnName).apply(object) == null) {
        return false;
      }
    }
    return true;
  }
}
