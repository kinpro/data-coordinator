package com.socrata.datacoordinator.service

import com.typesafe.config.Config
import com.socrata.datacoordinator.common.DataSourceConfig

class ServiceConfig(val config: Config, root: String) {
  private def k(field: String) = root + "." + field
  val secondary = new SecondaryConfig(config, k("secondary"))
  val network = new NetworkConfig(config, k("network"))
  val curator = new CuratorConfig(config, k("curator"))
  val advertisement = new AdvertisementConfig(config, k("service-advertisement"))
  val dataSource = new DataSourceConfig(config, k("database"))
  val logProperties = config.getConfig(k("log4j"))
  val commandReadLimit = config.getBytes(k("command-read-limit")).longValue
  val allowDdlOnPublishedCopies = config.getBoolean(k("allow-ddl-on-published-copies"))
  val instance = config.getString(k("instance"))

  require(instance.matches("[a-zA-Z0-9._]+"), "Instance names must consist of only ASCII letters, numbers, periods, and underscores")
}
