package bootstrap.liftweb.checks.migration

import doobie.ConnectionIO
import doobie.syntax.string.*

/**
 * Mixin that provides set of utils for SQL migration
 */
trait DbCommonMigration {

  def isColumnNullable(tableName: String, columnName: String): ConnectionIO[Boolean] = {
    sql"""
      select count(*)
      from information_schema.columns
      where table_name = $tableName
      and column_name = $columnName
      and is_nullable = 'YES'
    """.query[Int].unique.map(_ > 0)
  }

}
