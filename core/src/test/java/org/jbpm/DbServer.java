package org.jbpm;

import java.util.EnumSet;

import org.hibernate.cfg.Configuration;
import org.hibernate.tool.hbm2ddl.SchemaExport;
import org.hibernate.tool.schema.TargetType;
import org.hsqldb.Server;
import org.hsqldb.util.DatabaseManager;

/**
 * use this in combination with the HQL editor of the hibernate plugin.
 */
public class DbServer {

  public static void main(String[] args) {
      /*
    Configuration configuration = new Configuration();
    configuration.configure();
    new SchemaExport().create(EnumSet.of( TargetType.STDOUT, TargetType.DATABASE ), configuration);

    Server server = new Server();
    server.setSilent(false);
    server.setDatabaseName(0, "jbpm");
    server.setDatabasePath(0, "mem:jbpm");
    server.setPort(9001);
    server.start();

    DatabaseManager.main(new String[]{"-url", "jdbc:hsqldb:hsql://localhost:9001/jbpm"});
    */
  }
}
