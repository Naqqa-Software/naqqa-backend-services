package com.naqqa.sso.session;

import java.util.List;
import java.util.Optional;

public interface SessionStore {
    void save(Session session);

    boolean replace(Session session);

    Optional<Session> find(String idHash);

    void delete(String idHash);

    List<Session> listByUser(String userId);

    int deleteByUser(String userId);
}
