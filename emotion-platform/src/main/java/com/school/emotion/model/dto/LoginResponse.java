package com.school.emotion.model.dto;

import java.util.Map;

public class LoginResponse {
    private String token;
    private UserInfo user;

    public LoginResponse(String token, String username, String name, String role,
                          Long gradeId, Long classId) {
        this.token = token;
        this.user = new UserInfo(username, name, role, gradeId, classId);
    }

    public String getToken() { return token; }
    public UserInfo getUser() { return user; }

    public static class UserInfo {
        private String username;
        private String name;
        private String role;
        private Long gradeId;
        private Long classId;

        public UserInfo(String username, String name, String role, Long gradeId, Long classId) {
            this.username = username;
            this.name = name;
            this.role = role;
            this.gradeId = gradeId;
            this.classId = classId;
        }

        public String getUsername() { return username; }
        public String getName() { return name; }
        public String getRole() { return role; }
        public Long getGradeId() { return gradeId; }
        public Long getClassId() { return classId; }
    }
}
