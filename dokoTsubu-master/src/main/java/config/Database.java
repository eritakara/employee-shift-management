package config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import util.PasswordUtil;

public final class Database {
  private static String jdbcUrl;
  private static String jdbcUser;
  private static String jdbcPassword;

  private Database() { }

  private static void logEnvironmentState() {
    String appEnv = System.getenv("APP_ENV");
    boolean isProduction = "production".equalsIgnoreCase(appEnv);
    String jdbcUrlVal = System.getenv("JDBC_URL");
    if (jdbcUrlVal == null || jdbcUrlVal.isBlank()) jdbcUrlVal = System.getenv("DB_URL");
    if (jdbcUrlVal == null || jdbcUrlVal.isBlank()) jdbcUrlVal = System.getenv("DB_JDBC_URL");
    if (jdbcUrlVal == null || jdbcUrlVal.isBlank()) jdbcUrlVal = System.getenv("DATABASE_URL");
    if (jdbcUrlVal == null || jdbcUrlVal.isBlank()) jdbcUrlVal = System.getProperty("shiftapp.jdbcUrl");

    String dbUserVal = System.getenv("DB_USER");
    if (dbUserVal == null || dbUserVal.isBlank()) dbUserVal = System.getProperty("shiftapp.dbUser");

    String dbPasswordVal = System.getenv("DB_PASSWORD");
    if (dbPasswordVal == null || dbPasswordVal.isBlank()) dbPasswordVal = System.getProperty("shiftapp.dbPassword");

    String hrEmailVal = System.getenv("INITIAL_HR_EMAIL");
    if (hrEmailVal == null || hrEmailVal.isBlank()) hrEmailVal = System.getProperty("shiftapp.initialHrEmail");

    String hrPasswordVal = System.getenv("INITIAL_HR_PASSWORD");
    if (hrPasswordVal == null || hrPasswordVal.isBlank()) hrPasswordVal = System.getProperty("shiftapp.initialHrPassword");

    System.out.println("=== Database Environment Check ===");
    System.out.println("APP_ENV = " + appEnv + " (isProduction: " + isProduction + ")");
    System.out.println("JDBC_URL configured: " + (jdbcUrlVal != null && !jdbcUrlVal.isBlank()));
    System.out.println("DB_USER configured: " + (dbUserVal != null && !dbUserVal.isBlank()));
    System.out.println("DB_PASSWORD configured: " + (dbPasswordVal != null && !dbPasswordVal.isBlank()));
    System.out.println("INITIAL_HR_EMAIL configured: " + (hrEmailVal != null && !hrEmailVal.isBlank()));
    System.out.println("INITIAL_HR_PASSWORD configured: " + (hrPasswordVal != null && !hrPasswordVal.isBlank()));
    System.out.println("==================================");
  }

  public static synchronized void initialize() {
    if (jdbcUrl != null) return;
    logEnvironmentState();
    try {
      String url = configuredJdbcUrl();
      if (url != null) {
        jdbcUrl = url;
        jdbcUser = setting("shiftapp.dbUser", "DB_USER", setting("shiftapp.jdbcUser", "JDBC_USER", null));
        jdbcPassword = setting("shiftapp.dbPassword", "DB_PASSWORD", setting("shiftapp.jdbcPassword", "JDBC_PASSWORD", null));
      } else {
        jdbcUrl = defaultH2Url();
        jdbcUser = setting("shiftapp.dbUser", "DB_USER", setting("shiftapp.jdbcUser", "JDBC_USER", "sa"));
        jdbcPassword = setting("shiftapp.dbPassword", "DB_PASSWORD", setting("shiftapp.jdbcPassword", "JDBC_PASSWORD", ""));
      }
      
      loadJdbcDriver();
      
      System.out.println("Connecting to database: " + jdbcUrl + " (User: " + (jdbcUser != null ? jdbcUser : "none") + ")...");
      try (Connection connection = getConnection()) {
        System.out.println("Database connection established successfully.");
        
        System.out.println("Starting table creation (schema)...");
        createSchema(connection);
        System.out.println("Table creation (schema) completed successfully.");
        
        if (flag("shiftapp.seedDemo", "BASE_SEED", true)) {
          System.out.println("Starting master data and initial admin (HR) creation...");
          seed(connection);
          System.out.println("Master data and initial admin (HR) creation completed successfully.");
        }
      }
      System.out.println("Database initialization completed successfully.");
    } catch (Throwable e) {
      System.err.println("CRITICAL ERROR: Database initialization failed!");
      e.printStackTrace(System.err);
      jdbcUrl = null;
      jdbcUser = null;
      jdbcPassword = null;
      if (e instanceof RuntimeException) throw (RuntimeException) e;
      throw new IllegalStateException("Failed to initialize database", e);
    }
  }

  public static Connection getConnection() throws SQLException {
    if (jdbcUrl == null) initialize();
    if (jdbcUser == null) {
      return DriverManager.getConnection(jdbcUrl);
    }
    return DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword);
  }

  private static String defaultH2Url() throws IOException {
    String dataDirSetting = setting("shiftapp.dataDir", "SHIFTFLOW_DATA_DIR", null);
    Path dataDir = dataDirSetting == null || dataDirSetting.isBlank()
        ? Path.of(System.getProperty("catalina.base", System.getProperty("java.io.tmpdir")), "data")
        : Path.of(dataDirSetting);
    Files.createDirectories(dataDir);
    return "jdbc:h2:file:" + dataDir.resolve("shiftapp").toAbsolutePath() + ";AUTO_SERVER=TRUE";
  }

  private static String configuredJdbcUrl() throws IOException {
    String configured = System.getProperty("shiftapp.jdbcUrl");
    if (configured == null || configured.isBlank()) {
      configured = System.getenv("JDBC_URL");
    }
    if (configured == null || configured.isBlank()) {
      configured = System.getenv("DB_URL");
    }
    if (configured == null || configured.isBlank()) {
      configured = System.getenv("DB_JDBC_URL");
    }
    if (configured == null || configured.isBlank()) {
      configured = System.getenv("DATABASE_URL");
    }

    if (configured != null && !configured.isBlank()) {
      configured = configured.trim();
      if (configured.startsWith("postgres://")) {
        configured = "jdbc:postgresql://" + configured.substring("postgres://".length());
      } else if (configured.startsWith("postgresql://")) {
        configured = "jdbc:postgresql://" + configured.substring("postgresql://".length());
      }
      
      if (!configured.startsWith("jdbc:")) {
        throw new IllegalStateException("JDBC_URL/DB_URL must start with jdbc: (or postgresql://).");
      }
      return configured;
    }
    
    boolean isProduction = "true".equalsIgnoreCase(System.getenv("RENDER"))
        || "production".equalsIgnoreCase(System.getenv("APP_ENV"))
        || "true".equalsIgnoreCase(System.getenv("DB_REQUIRED"));
        
    if (isProduction) {
      throw new IllegalStateException("Database connection URL (JDBC_URL, DB_URL, or DATABASE_URL) is required in production environment.");
    }
    
    return null;
  }

  private static void loadJdbcDriver() throws ClassNotFoundException {
    String driver = setting("shiftapp.dbDriver", "DB_DRIVER", null);
    if (driver != null && !driver.isBlank()) {
      System.out.println("Loading custom driver: " + driver);
      Class.forName(driver);
      System.out.println("Custom driver loaded successfully.");
    } else if (jdbcUrl.startsWith("jdbc:h2:")) {
      System.out.println("Loading H2 driver...");
      Class.forName("org.h2.Driver");
      System.out.println("H2 driver loaded successfully.");
    } else if (jdbcUrl.startsWith("jdbc:postgresql:")) {
      System.out.println("Loading PostgreSQL driver...");
      Class.forName("org.postgresql.Driver");
      System.out.println("PostgreSQL driver loaded successfully.");
    } else {
      System.out.println("Warning: Unknown JDBC URL format. Skipping explicit driver class loading.");
    }
  }

  private static String setting(String propertyName, String envName, String defaultValue) {
    String property = System.getProperty(propertyName);
    if (property != null && !property.isBlank()) return property;
    String env = System.getenv(envName);
    if (env != null && !env.isBlank()) return env;
    return defaultValue;
  }

  private static String first(String propertyName, String envName, String fallback) {
    String value = setting(propertyName, envName, null);
    return value == null || value.isBlank() ? fallback : value;
  }

  private static boolean flag(String propertyName, String envName, boolean defaultValue) {
    String value = setting(propertyName, envName, null);
    return value == null || value.isBlank() ? defaultValue : Boolean.parseBoolean(value);
  }

  private static void createSchema(Connection c) throws SQLException {
    String[] statements = {
      "CREATE TABLE IF NOT EXISTS branches (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, name VARCHAR(100) NOT NULL UNIQUE, active BOOLEAN NOT NULL DEFAULT TRUE)",
      "CREATE TABLE IF NOT EXISTS departments (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, name VARCHAR(100) NOT NULL UNIQUE, active BOOLEAN NOT NULL DEFAULT TRUE)",
      "CREATE TABLE IF NOT EXISTS employment_types (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, name VARCHAR(50) NOT NULL UNIQUE, active BOOLEAN NOT NULL DEFAULT TRUE)",
      "CREATE TABLE IF NOT EXISTS app_settings (setting_key VARCHAR(80) PRIMARY KEY, setting_value VARCHAR(500) NOT NULL, description VARCHAR(500), updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)",
      "CREATE TABLE IF NOT EXISTS company_holidays (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, holiday_date DATE NOT NULL, branch_id BIGINT, name VARCHAR(100) NOT NULL, UNIQUE(holiday_date,branch_id), FOREIGN KEY(branch_id) REFERENCES branches(id))",
      "CREATE TABLE IF NOT EXISTS qualification_types (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, name VARCHAR(100) NOT NULL UNIQUE, active BOOLEAN NOT NULL DEFAULT TRUE)",
      "CREATE TABLE IF NOT EXISTS users (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, employee_number VARCHAR(30) NOT NULL UNIQUE, name VARCHAR(100) NOT NULL, email VARCHAR(200) NOT NULL UNIQUE, password_hash VARCHAR(500) NOT NULL, hire_date DATE NOT NULL, branch_id BIGINT NOT NULL, department_id BIGINT NOT NULL, employment_type_id BIGINT NOT NULL, role VARCHAR(20) NOT NULL, locale VARCHAR(5) NOT NULL DEFAULT 'ja', active BOOLEAN NOT NULL DEFAULT TRUE, FOREIGN KEY(branch_id) REFERENCES branches(id), FOREIGN KEY(department_id) REFERENCES departments(id), FOREIGN KEY(employment_type_id) REFERENCES employment_types(id))",
      "CREATE TABLE IF NOT EXISTS qualifications (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, user_id BIGINT NOT NULL, name VARCHAR(100) NOT NULL, expires_on DATE, active BOOLEAN NOT NULL DEFAULT TRUE, FOREIGN KEY(user_id) REFERENCES users(id))",
      "CREATE TABLE IF NOT EXISTS work_types (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, code VARCHAR(30) NOT NULL UNIQUE, name_ja VARCHAR(50) NOT NULL, name_en VARCHAR(50) NOT NULL, start_time TIME, end_time TIME, crosses_midnight BOOLEAN NOT NULL DEFAULT FALSE, break_minutes INT NOT NULL DEFAULT 0, required_staff INT NOT NULL DEFAULT 0, active BOOLEAN NOT NULL DEFAULT TRUE)",
      "CREATE TABLE IF NOT EXISTS shifts (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, user_id BIGINT NOT NULL, work_date DATE NOT NULL, work_type_code VARCHAR(30) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'DRAFT', note VARCHAR(500), updated_by BIGINT NOT NULL, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE(user_id, work_date), FOREIGN KEY(user_id) REFERENCES users(id), FOREIGN KEY(updated_by) REFERENCES users(id))",
      "CREATE TABLE IF NOT EXISTS shift_preference_submissions (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, user_id BIGINT NOT NULL, target_month DATE NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'DRAFT', submitted_at TIMESTAMP, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, reviewed_by BIGINT, reviewed_at TIMESTAMP, UNIQUE(user_id,target_month), FOREIGN KEY(user_id) REFERENCES users(id), FOREIGN KEY(reviewed_by) REFERENCES users(id))",
      "CREATE TABLE IF NOT EXISTS shift_preferences (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, submission_id BIGINT NOT NULL, preference_date DATE NOT NULL, request_type VARCHAR(20) NOT NULL, note VARCHAR(500), UNIQUE(submission_id,preference_date), FOREIGN KEY(submission_id) REFERENCES shift_preference_submissions(id) ON DELETE CASCADE)",
      "CREATE TABLE IF NOT EXISTS shift_change_requests (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, user_id BIGINT NOT NULL, work_date DATE NOT NULL, requested_work_type VARCHAR(30) NOT NULL, reason VARCHAR(1000) NOT NULL, urgent BOOLEAN NOT NULL DEFAULT FALSE, status VARCHAR(20) NOT NULL DEFAULT 'PENDING', decided_by BIGINT, decided_at TIMESTAMP, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY(user_id) REFERENCES users(id), FOREIGN KEY(decided_by) REFERENCES users(id))",
      "CREATE TABLE IF NOT EXISTS leave_balances (user_id BIGINT PRIMARY KEY, days_remaining DECIMAL(6,2) NOT NULL DEFAULT 10, hourly_used INT NOT NULL DEFAULT 0, last_granted_on DATE, FOREIGN KEY(user_id) REFERENCES users(id))",
      "CREATE TABLE IF NOT EXISTS leave_rule_config (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, effective_from DATE NOT NULL UNIQUE, attendance_threshold DECIMAL(4,3) NOT NULL DEFAULT 0.800, hourly_limit_days INT NOT NULL DEFAULT 5, hours_per_day INT NOT NULL DEFAULT 8, expiry_months INT NOT NULL DEFAULT 24, mandatory_days INT NOT NULL DEFAULT 5, active BOOLEAN NOT NULL DEFAULT TRUE)",
      "CREATE TABLE IF NOT EXISTS leave_grants (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, user_id BIGINT NOT NULL, grant_date DATE NOT NULL, expires_on DATE NOT NULL, days_granted DECIMAL(6,2) NOT NULL, days_remaining DECIMAL(6,2) NOT NULL, attendance_rate DECIMAL(5,4), source VARCHAR(30) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE(user_id,grant_date), FOREIGN KEY(user_id) REFERENCES users(id))",
      "CREATE TABLE IF NOT EXISTS leave_consumptions (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, request_id BIGINT NOT NULL, grant_id BIGINT NOT NULL, days_used DECIMAL(6,3) NOT NULL, hours_used INT NOT NULL DEFAULT 0, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE(request_id,grant_id), FOREIGN KEY(grant_id) REFERENCES leave_grants(id))",
      "CREATE TABLE IF NOT EXISTS leave_history (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, user_id BIGINT NOT NULL, event_type VARCHAR(30) NOT NULL, event_date DATE NOT NULL, days DECIMAL(6,3) NOT NULL DEFAULT 0, hours INT NOT NULL DEFAULT 0, request_id BIGINT, grant_id BIGINT, note VARCHAR(500), created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY(user_id) REFERENCES users(id))",
      "CREATE TABLE IF NOT EXISTS leave_requests (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, user_id BIGINT NOT NULL, leave_date DATE NOT NULL, leave_unit VARCHAR(20) NOT NULL, hours INT, reason VARCHAR(1000) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'PENDING', decided_by BIGINT, decided_at TIMESTAMP, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY(user_id) REFERENCES users(id), FOREIGN KEY(decided_by) REFERENCES users(id))",
      "CREATE TABLE IF NOT EXISTS attendance (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, user_id BIGINT NOT NULL, work_date DATE NOT NULL, clock_in TIMESTAMP, clock_out TIMESTAMP, in_lat DECIMAL(10,7), in_lng DECIMAL(10,7), out_lat DECIMAL(10,7), out_lng DECIMAL(10,7), location_status VARCHAR(30), status VARCHAR(20) NOT NULL DEFAULT 'OPEN', finalized BOOLEAN NOT NULL DEFAULT FALSE, UNIQUE(user_id, work_date), FOREIGN KEY(user_id) REFERENCES users(id))",
      "CREATE TABLE IF NOT EXISTS attendance_adjustments (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, attendance_id BIGINT NOT NULL, requested_by BIGINT NOT NULL, requested_in TIMESTAMP, requested_out TIMESTAMP, reason VARCHAR(1000) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'PENDING', decided_by BIGINT, decided_at TIMESTAMP, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY(attendance_id) REFERENCES attendance(id), FOREIGN KEY(requested_by) REFERENCES users(id), FOREIGN KEY(decided_by) REFERENCES users(id))",
      "CREATE TABLE IF NOT EXISTS delegations (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, manager_id BIGINT NOT NULL, delegate_id BIGINT NOT NULL, starts_on DATE NOT NULL, ends_on DATE NOT NULL, active BOOLEAN NOT NULL DEFAULT TRUE, FOREIGN KEY(manager_id) REFERENCES users(id), FOREIGN KEY(delegate_id) REFERENCES users(id))",
      "CREATE TABLE IF NOT EXISTS notifications (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, user_id BIGINT NOT NULL, type VARCHAR(50) NOT NULL, title VARCHAR(200) NOT NULL, message VARCHAR(1000) NOT NULL, target_url VARCHAR(500), is_read BOOLEAN NOT NULL DEFAULT FALSE, email_status VARCHAR(20) NOT NULL DEFAULT 'QUEUED', created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY(user_id) REFERENCES users(id))",
      "CREATE TABLE IF NOT EXISTS account_tokens (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, user_id BIGINT NOT NULL, token VARCHAR(100) NOT NULL UNIQUE, token_type VARCHAR(20) NOT NULL, expires_at TIMESTAMP NOT NULL, used_at TIMESTAMP, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY(user_id) REFERENCES users(id))",
      "CREATE TABLE IF NOT EXISTS mail_outbox (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, recipient VARCHAR(200) NOT NULL, subject VARCHAR(300) NOT NULL, body VARCHAR(4000) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'QUEUED', attempts INT NOT NULL DEFAULT 0, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, sent_at TIMESTAMP)",
      "CREATE TABLE IF NOT EXISTS audit_logs (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, actor_id BIGINT, action VARCHAR(80) NOT NULL, target_type VARCHAR(80) NOT NULL, target_id VARCHAR(80), before_value TEXT, after_value TEXT, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY(actor_id) REFERENCES users(id))"
    };
    try (Statement statement = c.createStatement()) {
      for (String sql : statements) {
        try {
          statement.execute(sql);
        } catch (SQLException e) {
          System.err.println("SQL Execution Failed on DDL: " + sql);
          throw e;
        }
      }
      
      String[] alters = {
        "ALTER TABLE mail_outbox ADD COLUMN IF NOT EXISTS last_error VARCHAR(2000)",
        "ALTER TABLE mail_outbox ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMP",
        "ALTER TABLE users ADD COLUMN IF NOT EXISTS weekly_work_days INT NOT NULL DEFAULT 5",
        "ALTER TABLE users ADD COLUMN IF NOT EXISTS weekly_work_hours DECIMAL(5,2) NOT NULL DEFAULT 40",
        "ALTER TABLE users ADD COLUMN IF NOT EXISTS deactivated_at TIMESTAMP",
        "ALTER TABLE leave_consumptions ADD COLUMN IF NOT EXISTS restored BOOLEAN NOT NULL DEFAULT FALSE",
        "ALTER TABLE qualifications ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE",
        "ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS target_user_id BIGINT"
      };
      for (String sql : alters) {
        try {
          statement.execute(sql);
        } catch (SQLException e) {
          System.err.println("SQL Execution Failed on ALTER: " + sql);
          throw e;
        }
      }
    }
  }

  private static void seed(Connection c) throws SQLException, IOException {
    if (count(c, "branches") == 0) {
      System.out.println("Seeding master data (branches, departments, employment_types)...");
      try {
        insertNames(c, "branches", new String[]{"本社", "北部支店", "中部支店", "那覇支店", "南部支店", "宮古支店", "石垣支店"});
        insertNames(c, "departments", new String[]{"営業部", "経理部", "総務部", "人事部"});
        insertNames(c, "employment_types", new String[]{"正社員", "契約社員", "パート", "アルバイト"});
      } catch (SQLException e) {
        System.err.println("Seeding basic master data failed.");
        throw e;
      }
    }
    if (count(c, "work_types") == 0) {
      System.out.println("Seeding work types...");
      String sql = "INSERT INTO work_types(code,name_ja,name_en,start_time,end_time,crosses_midnight,break_minutes,required_staff) VALUES(?,?,?,?,?,?,?,?)";
      try (PreparedStatement p = c.prepareStatement(sql)) {
        insertWorkType(p, "DAY", "日勤", "Day", "08:00:00", "17:00:00", false, 60, 5);
        insertWorkType(p, "NIGHT", "夜勤", "Night", "17:00:00", "08:00:00", true, 120, 7);
        insertWorkType(p, "OFF", "休み", "Off", null, null, false, 0, 0);
        insertWorkType(p, "LEAVE", "有休", "Paid leave", null, null, false, 0, 0);
        insertWorkType(p, "AM_LEAVE", "午前休", "AM leave", "13:00:00", "17:00:00", false, 0, 0);
        insertWorkType(p, "PM_LEAVE", "午後休", "PM leave", "08:00:00", "12:00:00", false, 0, 0);
      } catch (SQLException e) {
        System.err.println("Seeding basic work types failed.");
        throw e;
      }
    }
    try (Statement s = c.createStatement()) {
      try {
        s.executeUpdate("INSERT INTO work_types(code,name_ja,name_en,start_time,end_time,crosses_midnight,break_minutes,required_staff) "
            + "SELECT 'NIGHT_OFF','夜勤明け','Post-night rest',NULL,NULL,FALSE,0,0 WHERE NOT EXISTS(SELECT 1 FROM work_types WHERE code='NIGHT_OFF')");
      } catch (SQLException e) {
        System.err.println("Seeding work type NIGHT_OFF failed.");
        throw e;
      }
    }
    if (count(c, "users") == 0) {
      boolean isProduction = "true".equalsIgnoreCase(System.getenv("RENDER"))
          || "production".equalsIgnoreCase(System.getenv("APP_ENV"))
          || "true".equalsIgnoreCase(System.getenv("DB_REQUIRED"));

      String hrEmail = setting("shiftapp.initialHrEmail", "INITIAL_HR_EMAIL", "hr@example.com");
      String hrPassword = setting("shiftapp.initialHrPassword", "INITIAL_HR_PASSWORD", "Password1!");

      if (isProduction && "hr@example.com".equals(hrEmail) && "Password1!".equals(hrPassword)) {
        throw new IllegalStateException("INITIAL_HR_EMAIL and INITIAL_HR_PASSWORD environment variables must be configured in production for security reasons.");
      }

      System.out.println("Creating initial HR user: " + hrEmail);
      try {
        addUser(c, "HR001", "人事担当", hrEmail, hrPassword, "HR", 1, 4);
      } catch (SQLException e) {
        System.err.println("Seeding initial HR user failed.");
        throw e;
      }

      if (!isProduction) {
        System.out.println("Seeding demo users...");
        try {
          addUser(c, "MG001", "那覇 店長", "manager@example.com", "MANAGER", 4, 1);
          addUser(c, "EM001", "山田 花子", "employee@example.com", "EMPLOYEE", 4, 1);
          addUser(c, "EM002", "佐藤 太郎", "sato@example.com", "EMPLOYEE", 4, 1);
        } catch (SQLException e) {
          System.err.println("Seeding demo users failed.");
          throw e;
        }
      }

      try (Statement s = c.createStatement()) {
        s.executeUpdate("INSERT INTO leave_balances(user_id,days_remaining,hourly_used,last_granted_on) SELECT id,10,0,CURRENT_DATE FROM users");
      } catch (SQLException e) {
        System.err.println("Seeding leave balances failed.");
        throw e;
      }
    }
    if (count(c, "leave_rule_config") == 0) {
      System.out.println("Seeding leave rule configuration...");
      try (Statement s = c.createStatement()) {
        s.executeUpdate("INSERT INTO leave_rule_config(effective_from,attendance_threshold,hourly_limit_days,hours_per_day,expiry_months,mandatory_days) VALUES(DATE '2019-04-01',0.800,5,8,24,5)");
      } catch (SQLException e) {
        System.err.println("Seeding leave rule configuration failed.");
        throw e;
      }
    }
    if (count(c, "app_settings") == 0) {
      System.out.println("Seeding app settings...");
      String sql = "INSERT INTO app_settings(setting_key,setting_value,description) VALUES(?,?,?)";
      try (PreparedStatement p = c.prepareStatement(sql)) {
        insertSetting(p, "SHIFT_SUBMISSION_DAY", "15", "翌月希望シフトの提出締切日");
        insertSetting(p, "ALLOW_CONFIRM_WITH_WARNINGS", "true", "理由入力により警告付き確定を許可");
        insertSetting(p, "LEAVE_ALLOW_PAST", "false", "過去日の有休申請を許可");
        insertSetting(p, "LEAVE_MIN_NOTICE_DAYS", "1", "有休申請の最低事前日数");
        insertSetting(p, "MONTHLY_CLOSE_DAY", "5", "翌月の勤怠締切日");
        insertSetting(p, "RETENTION_YEARS", "5", "業務データ保存年数");
        insertSetting(p, "LOCATION_REQUIRED", "false", "位置情報なしの打刻を禁止");
        insertSetting(p, "MAX_CONCURRENT_USERS", "100", "想定最大同時利用者数");
      } catch (SQLException e) {
        System.err.println("Seeding app settings failed.");
        throw e;
      }
    }
    if (flag("shiftapp.demoSeed", "DEMO_SEED", false)) {
      System.out.println("Seeding demo CSV tables...");
      try {
        importDemoUsers(c);
        importDemoShifts(c);
        importDemoAttendance(c);
      } catch (Exception e) {
        System.err.println("Seeding demo CSV data failed.");
        throw e;
      }
    }
    if (count(c, "leave_grants") == 0 && count(c, "users") > 0) {
      System.out.println("Seeding migration leave grants...");
      try (Statement s = c.createStatement()) {
        String add24MonthsSql;
        if (jdbcUrl.startsWith("jdbc:h2:")) {
          add24MonthsSql = "DATEADD('MONTH',24,COALESCE(b.last_granted_on,CURRENT_DATE))";
        } else {
          add24MonthsSql = "COALESCE(b.last_granted_on,CURRENT_DATE) + INTERVAL '24 month'";
        }
        s.executeUpdate("INSERT INTO leave_grants(user_id,grant_date,expires_on,days_granted,days_remaining,attendance_rate,source) "
            + "SELECT b.user_id,COALESCE(b.last_granted_on,CURRENT_DATE)," + add24MonthsSql + ","
            + "b.days_remaining,b.days_remaining,1.0,'MIGRATION' FROM leave_balances b WHERE b.days_remaining>0");
      } catch (SQLException e) {
        System.err.println("Seeding migration leave grants failed.");
        throw e;
      }
    }
  }

  private static void importDemoUsers(Connection c) throws SQLException, IOException {
    InputStream input = Database.class.getResourceAsStream("/data/demo-users.csv");
    if (input == null) throw new IOException("Demo user CSV was not found: /data/demo-users.csv");
    String userSql = "INSERT INTO users(employee_number,name,email,password_hash,hire_date,branch_id,department_id,employment_type_id,role) "
        + "SELECT ?,?,?,?,?,?,?,?,? WHERE NOT EXISTS(SELECT 1 FROM users WHERE employee_number=?)";
    String balanceSql;
    if (jdbcUrl.startsWith("jdbc:h2:")) {
      balanceSql = "MERGE INTO leave_balances(user_id,days_remaining,hourly_used,last_granted_on) KEY(user_id) "
          + "SELECT id,10,0,CURRENT_DATE FROM users WHERE employee_number=?";
    } else {
      balanceSql = "INSERT INTO leave_balances(user_id,days_remaining,hourly_used,last_granted_on) "
          + "SELECT id,10,0,CURRENT_DATE FROM users WHERE employee_number=? "
          + "ON CONFLICT (user_id) DO UPDATE SET days_remaining=10, last_granted_on=CURRENT_DATE";
    }
    String updateSql = "UPDATE users SET name=?,email=?,role=?,branch_id=?,department_id=?,employment_type_id=? WHERE employee_number=?";
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
         PreparedStatement exists = c.prepareStatement("SELECT 1 FROM users WHERE employee_number=?");
         PreparedStatement user = c.prepareStatement(userSql); PreparedStatement update = c.prepareStatement(updateSql);
         PreparedStatement balance = c.prepareStatement(balanceSql)) {
      String header = reader.readLine();
      if (!"employee_number,name,email,role,branch_id,department_id,employment_type_id".equals(header)) {
        throw new IOException("Unexpected demo user CSV header");
      }
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank() || line.startsWith("#")) continue;
        String[] values = line.split(",", -1);
        if (values.length != 7) throw new IOException("Invalid demo user CSV row: " + line);
        exists.setString(1, values[0]);
        try (ResultSet found = exists.executeQuery()) {
          if (!found.next()) {
            user.setString(1, values[0]); user.setString(2, values[1]); user.setString(3, values[2]);
            user.setString(4, PasswordUtil.hash("Password1!")); user.setObject(5, LocalDate.of(2024, 4, 1));
            user.setLong(6, Long.parseLong(values[4])); user.setLong(7, Long.parseLong(values[5]));
            user.setLong(8, Long.parseLong(values[6])); user.setString(9, values[3]); user.setString(10, values[0]);
            user.executeUpdate();
          } else {
            update.setString(1, values[1]); update.setString(2, values[2]); update.setString(3, values[3]);
            update.setLong(4, Long.parseLong(values[4])); update.setLong(5, Long.parseLong(values[5]));
            update.setLong(6, Long.parseLong(values[6])); update.setString(7, values[0]); update.executeUpdate();
          }
        }
        balance.setString(1, values[0]); balance.executeUpdate();
      }
    }
  }

  private static void importDemoShifts(Connection c) throws SQLException, IOException {
    InputStream input = Database.class.getResourceAsStream("/data/demo-shifts.csv");
    if (input == null) throw new IOException("Demo shift CSV was not found: /data/demo-shifts.csv");

    String sql;
    if (jdbcUrl.startsWith("jdbc:h2:")) {
      sql = "MERGE INTO shifts(user_id,work_date,work_type_code,status,note,updated_by,updated_at) "
          + "KEY(user_id,work_date) SELECT u.id,?,?,?,?,u.id,CURRENT_TIMESTAMP FROM users u WHERE u.employee_number=?";
    } else {
      sql = "INSERT INTO shifts(user_id,work_date,work_type_code,status,note,updated_by,updated_at) "
          + "SELECT u.id,?,?,?,?,u.id,CURRENT_TIMESTAMP FROM users u WHERE u.employee_number=? "
          + "ON CONFLICT (user_id,work_date) DO UPDATE SET work_type_code=EXCLUDED.work_type_code, "
          + "status=EXCLUDED.status, note=EXCLUDED.note, updated_by=EXCLUDED.updated_by, updated_at=CURRENT_TIMESTAMP";
    }
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
         PreparedStatement p = c.prepareStatement(sql)) {
      String header = reader.readLine();
      if (!"employee_number,month,work_pattern,leave_day,half_leave_day,half_leave_type,status,note".equals(header)) {
        throw new IOException("Unexpected demo shift CSV header");
      }
      String line;
      int lineNumber = 1;
      while ((line = reader.readLine()) != null) {
        lineNumber++;
        if (line.isBlank() || line.startsWith("#")) continue;
        String[] values = line.split(",", -1);
        if (values.length != 8) throw new IOException("Invalid demo shift CSV at line " + lineNumber);
        java.time.YearMonth month = java.time.YearMonth.parse(values[1]);
        String[] pattern = values[2].split("\\|");
        int leaveDay = values[3].isBlank() ? -1 : Integer.parseInt(values[3]);
        int halfLeaveDay = values[4].isBlank() ? -1 : Integer.parseInt(values[4]);
        for (int day = 1; day <= month.lengthOfMonth(); day++) {
          String workType = day == leaveDay ? "LEAVE" : day == halfLeaveDay ? values[5] : pattern[(day - 1) % pattern.length];
          p.setObject(1, month.atDay(day)); p.setString(2, workType); p.setString(3, values[6]);
          p.setString(4, values[7].isBlank() ? null : values[7]); p.setString(5, values[0]); p.addBatch();
        }
      }
      p.executeBatch();
    }
  }

  private static void importDemoAttendance(Connection c) throws SQLException, IOException {
    InputStream input = Database.class.getResourceAsStream("/data/demo-attendance.csv");
    if (input == null) throw new IOException("Demo attendance CSV was not found: /data/demo-attendance.csv");

    String sql;
    if (jdbcUrl.startsWith("jdbc:h2:")) {
      sql = "MERGE INTO attendance(user_id,work_date,clock_in,clock_out,in_lat,in_lng,out_lat,out_lng,location_status,status,finalized) "
          + "KEY(user_id,work_date) SELECT u.id,?,?,?,?,?,?,?,?,?,? FROM users u WHERE u.employee_number=?";
    } else {
      sql = "INSERT INTO attendance(user_id,work_date,clock_in,clock_out,in_lat,in_lng,out_lat,out_lng,location_status,status,finalized) "
          + "SELECT u.id,?,?,?,?,?,?,?,?,?,? FROM users u WHERE u.employee_number=? "
          + "ON CONFLICT (user_id,work_date) DO UPDATE SET clock_in=EXCLUDED.clock_in, clock_out=EXCLUDED.clock_out, "
          + "in_lat=EXCLUDED.in_lat, in_lng=EXCLUDED.in_lng, out_lat=EXCLUDED.out_lat, out_lng=EXCLUDED.out_lng, "
          + "location_status=EXCLUDED.location_status, status=EXCLUDED.status, finalized=EXCLUDED.finalized";
    }
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
         PreparedStatement p = c.prepareStatement(sql)) {
      String header = reader.readLine();
      if (!"employee_number,work_date,clock_in,clock_out,in_lat,in_lng,out_lat,out_lng,location_status,status,finalized".equals(header)) {
        throw new IOException("Unexpected demo attendance CSV header");
      }
      String line;
      int lineNumber = 1;
      while ((line = reader.readLine()) != null) {
        lineNumber++;
        if (line.isBlank() || line.startsWith("#")) continue;
        String[] values = line.split(",", -1);
        if (values.length != 11) throw new IOException("Invalid demo attendance CSV at line " + lineNumber);
        p.setObject(1, LocalDate.parse(values[1]));
        p.setObject(2, values[2].isBlank() ? null : java.time.LocalDateTime.parse(values[2]));
        p.setObject(3, values[3].isBlank() ? null : java.time.LocalDateTime.parse(values[3]));
        p.setBigDecimal(4, number(values[4]));
        p.setBigDecimal(5, number(values[5]));
        p.setBigDecimal(6, number(values[6]));
        p.setBigDecimal(7, number(values[7]));
        p.setString(8, values[8].isBlank() ? null : values[8]);
        p.setString(9, values[9].isBlank() ? "OPEN" : values[9]);
        p.setBoolean(10, Boolean.parseBoolean(values[10]));
        p.setString(11, values[0]);
        p.addBatch();
      }
      p.executeBatch();
    }
  }

  private static int count(Connection c, String table) throws SQLException {
    try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
      rs.next(); return rs.getInt(1);
    }
  }

  private static void insertNames(Connection c, String table, String[] names) throws SQLException {
    try (PreparedStatement p = c.prepareStatement("INSERT INTO " + table + "(name) VALUES(?)")) {
      for (String name : names) { p.setString(1, name); p.addBatch(); }
      p.executeBatch();
    }
  }

  private static java.sql.Time parseTime(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String trimmed = value.trim();
    if (trimmed.length() == 5) {
      trimmed = trimmed + ":00";
    }
    return java.sql.Time.valueOf(trimmed);
  }

  private static void setTimeOrNull(PreparedStatement p, int parameterIndex, String value) throws SQLException {
    java.sql.Time t = parseTime(value);
    if (t == null) {
      p.setNull(parameterIndex, java.sql.Types.TIME);
    } else {
      p.setTime(parameterIndex, t);
    }
  }

  private static void insertWorkType(PreparedStatement p, String code, String ja, String en,
      String start, String end, boolean overnight, int breakMinutes, int required) throws SQLException {
    p.setString(1, code); p.setString(2, ja); p.setString(3, en);
    setTimeOrNull(p, 4, start);
    setTimeOrNull(p, 5, end);
    p.setBoolean(6, overnight);
    p.setInt(7, breakMinutes); p.setInt(8, required); p.executeUpdate();
  }

  private static void addUser(Connection c, String number, String name, String email, String password,
      String role, long branchId, long departmentId) throws SQLException {
    String sql = "INSERT INTO users(employee_number,name,email,password_hash,hire_date,branch_id,department_id,employment_type_id,role) VALUES(?,?,?,?,?,?,?,?,?)";
    try (PreparedStatement p = c.prepareStatement(sql)) {
      p.setString(1, number); p.setString(2, name); p.setString(3, email);
      p.setString(4, PasswordUtil.hash(password));
      p.setObject(5, LocalDate.of(2024, 4, 1)); p.setLong(6, branchId);
      p.setLong(7, departmentId); p.setLong(8, 1); p.setString(9, role); p.executeUpdate();
    }
  }

  private static void addUser(Connection c, String number, String name, String email,
      String role, long branchId, long departmentId) throws SQLException {
    addUser(c, number, name, email, "Password1!", role, branchId, departmentId);
  }

  private static void insertSetting(PreparedStatement p, String key, String value, String description) throws SQLException {
    p.setString(1, key); p.setString(2, value); p.setString(3, description); p.executeUpdate();
  }

  private static BigDecimal number(String value) {
    return value == null || value.isBlank() ? null : new BigDecimal(value);
  }
}
