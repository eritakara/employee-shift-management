package service;

import dao.UserDAO;
import model.User;

public class SessionUserService {
  private final UserDAO users = new UserDAO();

  public User refresh(User sessionUser) {
    return sessionUser == null ? null : users.findActiveById(sessionUser.getId());
  }
}
