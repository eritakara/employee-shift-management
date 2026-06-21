package service;

import dao.Sql;
import java.util.List;
import java.util.Map;
import model.User;

public class MasterDataService {
  public List<Map<String, Object>> getMasterData(String type) {
    String table = switch (type) {
      case "branches" -> "branches"; case "departments" -> "departments";
      case "employment" -> "employment_types"; case "qualifications" -> "qualification_types"; default -> "work_types";
    };
    return Sql.query("SELECT * FROM " + table + " ORDER BY id");
  }

  public void addMaster(User actor, String type, String name) {
    if (!actor.isHr()) throw new SecurityException("人事担当者のみ操作できます。");
    String table = switch (type) { case "branches" -> "branches"; case "departments" -> "departments"; case "employment" -> "employment_types"; case "qualifications" -> "qualification_types"; default -> throw new IllegalArgumentException("マスタ種別が不正です。"); };
    long id = Sql.insert("INSERT INTO " + table + "(name) VALUES(?)", name);
    AuditService.record(actor.getId(), "CREATE_MASTER", table, String.valueOf(id), null, name);
  }

  public void toggleMaster(User actor, String type, long id, boolean active) {
    if (!actor.isHr()) throw new SecurityException("人事担当者のみ操作できます。");
    String table = switch (type) { case "branches" -> "branches"; case "departments" -> "departments"; case "employment" -> "employment_types"; case "qualifications" -> "qualification_types"; default -> throw new IllegalArgumentException("マスタ種別が不正です。"); };
    Sql.update("UPDATE " + table + " SET active=? WHERE id=?", active, id);
    AuditService.record(actor.getId(), "TOGGLE_MASTER", table, String.valueOf(id), null, String.valueOf(active));
  }

  public void updateMaster(User actor, String type, long id, String name, boolean active) {
    if (!actor.isHr()) throw new SecurityException("人事担当者のみ操作できます。");
    String table = switch (type) { case "branches" -> "branches"; case "departments" -> "departments"; case "employment" -> "employment_types"; case "qualifications" -> "qualification_types"; default -> throw new IllegalArgumentException("マスタ種別が不正です。"); };
    String normalized = name == null ? "" : name.trim();
    if (normalized.isEmpty()) throw new IllegalArgumentException("名称を入力してください。");
    Map<String, Object> before = Sql.one("SELECT name,active FROM " + table + " WHERE id=?", id);
    if (before.isEmpty()) throw new IllegalArgumentException("対象のマスタが見つかりません。");
    Sql.update("UPDATE " + table + " SET name=?,active=? WHERE id=?", normalized, active, id);
    AuditService.record(actor.getId(), "UPDATE_MASTER", table, String.valueOf(id), before.toString(), normalized + ":" + active);
  }

  public void updateWorkType(User actor, String code, String nameJa, String nameEn,
      String start, String end, int breakMinutes, int requiredStaff, boolean active) {
    if (!actor.isHr()) throw new SecurityException("人事担当者のみ操作できます。");
    Sql.update("UPDATE work_types SET name_ja=?,name_en=?,start_time=?,end_time=?,break_minutes=?,required_staff=?,active=? WHERE code=?",
        nameJa, nameEn, start == null || start.isBlank() ? null : java.time.LocalTime.parse(start),
        end == null || end.isBlank() ? null : java.time.LocalTime.parse(end), breakMinutes, requiredStaff, active, code);
    AuditService.record(actor.getId(), "UPDATE_WORK_TYPE", "WORK_TYPE", code, null, nameJa + ":" + breakMinutes + ":" + requiredStaff);
  }

  public List<Map<String, Object>> workTypes() {
    return Sql.query("SELECT * FROM work_types WHERE active=TRUE ORDER BY id");
  }
}
