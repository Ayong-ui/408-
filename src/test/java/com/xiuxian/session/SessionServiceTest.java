package com.xiuxian.session;

import static com.xiuxian.MutableClock.millis;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.xiuxian.TestBase;
import com.xiuxian.common.ApiException;

class SessionServiceTest extends TestBase {

    @Autowired
    SessionService sessions;

    private long startAt;

    /** 入定 30 分钟，调息 10 分钟，再续修 30 分钟；此刻是开始后 70 分钟。 */
    private long runPausedSession() {
        clock.set(LocalDateTime.of(2026, 10, 14, 9, 0));
        startAt = millis(LocalDateTime.of(2026, 10, 14, 9, 0));
        long id = sessions.start(methodId("大衍算经"), null).active().id();
        clock.advance(Duration.ofMinutes(30));
        sessions.pause(id);
        clock.advance(Duration.ofMinutes(10));
        sessions.resume(id);
        clock.advance(Duration.ofMinutes(30));
        return id;
    }

    @Test
    void elapsedExcludesPausedTime() {
        long id = runPausedSession();
        ActiveSession active = sessions.view().active();
        assertThat(active.id()).isEqualTo(id);
        assertThat(active.elapsedSeconds()).isEqualTo(60 * 60);
    }

    @Test
    void finishWithEndAtClipsTheRunningSegment() {
        long id = runPausedSession();

        Settlement s = sessions.finish(id, startAt + Duration.ofMinutes(50).toMillis());

        // [0,30) + [40,50) = 40 分钟
        assertThat(s.discarded()).isFalse();
        assertThat(s.session().seconds()).isEqualTo(40 * 60);
        assertThat(s.session().endedAt()).isEqualTo(startAt + Duration.ofMinutes(50).toMillis());
        assertThat(s.method().before().totalSeconds()).isZero();
        assertThat(s.method().after().totalSeconds()).isEqualTo(40 * 60);
        assertThat(s.realm().before().name()).isEqualTo("凡人");
        assertThat(s.realm().after().name()).isEqualTo("练气一层");
        assertThat(s.realm().breakthrough()).isTrue();
        assertThat(s.realm().major()).isTrue();
        assertThat(sessions.view().active()).isNull();
    }

    @Test
    void finishWithEndAtBeforeLaterSegmentDropsIt() {
        long id = runPausedSession();

        Settlement s = sessions.finish(id, startAt + Duration.ofMinutes(35).toMillis());

        // 第二段从 40 分钟开始，整段删掉；第一段完整保留
        assertThat(s.session().seconds()).isEqualTo(30 * 60);
        Integer segments = jdbc.queryForObject("SELECT COUNT(*) FROM segment WHERE session_id = ?", Integer.class, id);
        assertThat(segments).isEqualTo(1);
    }

    @Test
    void finishRejectsEndAtOutsideTheSession() {
        long id = runPausedSession();
        assertThatThrownBy(() -> sessions.finish(id, startAt - 1)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> sessions.finish(id, clock.millis() + 1)).isInstanceOf(ApiException.class);
    }

    @Test
    void underOneMinuteIsDiscarded() {
        clock.set(LocalDateTime.of(2026, 10, 14, 9, 0));
        long id = sessions.start(methodId("大衍算经"), null).active().id();
        clock.advance(Duration.ofSeconds(59));

        Settlement s = sessions.finish(id, null);

        assertThat(s.discarded()).isTrue();
        Integer left = jdbc.queryForObject("SELECT COUNT(*) FROM session", Integer.class);
        assertThat(left).isZero();
    }

    @Test
    void onlyOneActiveSessionAtATime() {
        clock.set(LocalDateTime.of(2026, 10, 14, 9, 0));
        sessions.start(methodId("大衍算经"), null);
        assertThatThrownBy(() -> sessions.start(methodId("万象阵图"), null))
                .isInstanceOf(ApiException.class)
                .hasMessage("已在入定中");
    }

    @Test
    void bodyPathCountsTowardBodyRealmNotCultivation() {
        clock.set(LocalDateTime.of(2026, 10, 14, 20, 0));
        long gym = methodId("金刚锻体诀");

        Settlement s = sessions.manual(gym, millis(LocalDateTime.of(2026, 10, 14, 18, 0)), 60, null, null);

        assertThat(s.realm().kind()).isEqualTo("BODY");
        assertThat(s.realm().after().name()).isEqualTo("炼皮");
        assertThat(s.realm().major()).isFalse();
    }
}
