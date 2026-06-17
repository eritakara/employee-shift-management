package model;
import java.io.Serializable;

public class User implements Serializable {
  private static final long serialVersionUID = 1L;

  private long id;
  private String employeeNumber;
  private String name;
  private String email;
  private String pass;
  private String role;
  private long branchId;
  private long departmentId;
  private String branchName;
  private String departmentName;
  private String locale;

  public User() { }
  public User(String name, String pass) {
    this.name = name;
    this.pass = pass;
  }
  public User(long id, String employeeNumber, String name, String email, String role,
      long branchId, long departmentId, String branchName, String departmentName, String locale) {
    this.id = id;
    this.employeeNumber = employeeNumber;
    this.name = name;
    this.email = email;
    this.role = role;
    this.branchId = branchId;
    this.departmentId = departmentId;
    this.branchName = branchName;
    this.departmentName = departmentName;
    this.locale = locale;
  }
  public long getId() { return id; }
  public String getEmployeeNumber() { return employeeNumber; }
  public String getName() { return name; }
  public String getEmail() { return email; }
  public String getPass() { return pass; }
  public String getRole() { return role; }
  public long getBranchId() { return branchId; }
  public long getDepartmentId() { return departmentId; }
  public String getBranchName() { return branchName; }
  public String getDepartmentName() { return departmentName; }
  public String getLocale() { return locale; }
  public boolean isHr() { return "HR".equals(role); }
  public boolean isManager() { return "MANAGER".equals(role); }
}
