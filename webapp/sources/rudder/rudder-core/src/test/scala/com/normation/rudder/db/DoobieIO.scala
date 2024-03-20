package com.normation.rudder.db

import doobie.*
import javax.sql.DataSource

class DoobieIO(dataSource: DataSource) extends Doobie(dataSource) {

  // a transactor with the cats.effect API, e.g. for doobie query type-checking
  val xaio: Transactor[cats.effect.IO] = {
    Transactor.fromDataSource[cats.effect.IO](dataSource, scala.concurrent.ExecutionContext.global)
  }

}
