package com.xiuxian.common;

import java.util.ArrayList;
import java.util.List;

/**
 * 三条境界阶梯。数据库只存时间，境界永远按这里的阈值现算，调曲线不用动数据。
 * <ul>
 *   <li>修为：除体修道脉外所有修炼时长之和。练气九层前快后慢，之后每个大境界分初期/中期/后期/大圆满。</li>
 *   <li>体修：健身这类 body 道脉的时长。</li>
 *   <li>熟练度：单个功法的累计时长。</li>
 * </ul>
 */
public final class Realms {

    private Realms() {
    }

    public enum Kind { CULTIVATION, BODY, MASTERY }

    /**
     * @param index        在阶梯上的序号，越大越高
     * @param major        大境界（练气、筑基……），大境界变化即"渡劫"
     * @param floorSeconds 当前境界的门槛
     * @param nextSeconds  下一境界的门槛，已到顶为 null
     * @param progress     当前境界内的进度 0..1
     */
    public record RealmInfo(String kind, long totalSeconds, int index, String major, String name,
                            long floorSeconds, String nextName, Long nextSeconds, double progress) {
    }

    private record Stage(String major, String name, long threshold) {
    }

    private static final long H = 3600;

    private static final List<Stage> CULTIVATION = buildCultivation();

    private static final List<Stage> BODY = List.of(
            new Stage("凡躯", "凡躯", 0),
            new Stage("炼皮", "炼皮", 1),
            new Stage("炼肉", "炼肉", 8 * H),
            new Stage("铜皮", "铜皮", 20 * H),
            new Stage("铁骨", "铁骨", 50 * H),
            new Stage("玉髓", "玉髓", 90 * H),
            new Stage("金身", "金身", 150 * H),
            new Stage("不灭", "不灭金身", 300 * H));

    private static final List<Stage> MASTERY = List.of(
            new Stage("未入门", "未入门", 0),
            new Stage("初窥门径", "初窥门径", 1),
            new Stage("略有小成", "略有小成", 5 * H),
            new Stage("登堂入室", "登堂入室", 20 * H),
            new Stage("融会贯通", "融会贯通", 60 * H),
            new Stage("炉火纯青", "炉火纯青", 150 * H),
            new Stage("登峰造极", "登峰造极", 300 * H));

    private static List<Stage> buildCultivation() {
        List<Stage> stages = new ArrayList<>();
        stages.add(new Stage("凡人", "凡人", 0));

        String[] layers = {"一", "二", "三", "四", "五", "六", "七", "八", "九"};
        long[] qiHours = {0, 4, 9, 15, 23, 33, 45, 60, 78};
        for (int i = 0; i < layers.length; i++) {
            // 第一次出关（哪怕只修了一秒）就是练气一层：引气入体
            long threshold = i == 0 ? 1 : qiHours[i] * H;
            stages.add(new Stage("练气", "练气" + layers[i] + "层", threshold));
        }

        String[] phases = {"初期", "中期", "后期", "大圆满"};
        addMajor(stages, "筑基", phases, 100, 150, 200, 250);
        addMajor(stages, "金丹", phases, 300, 400, 500, 600);
        addMajor(stages, "元婴", phases, 700, 850, 1000, 1150);
        addMajor(stages, "化神", phases, 1300, 1475, 1650, 1825);
        addMajor(stages, "炼虚", phases, 2000, 2250, 2500, 2750);
        addMajor(stages, "合体", phases, 3000, 3375, 3750, 4125);
        addMajor(stages, "大乘", phases, 4500, 5000, 5500, 6000);
        stages.add(new Stage("渡劫", "渡劫期", 6500 * H));
        return List.copyOf(stages);
    }

    private static void addMajor(List<Stage> stages, String major, String[] phases, long... hours) {
        for (int i = 0; i < phases.length; i++) {
            stages.add(new Stage(major, major + phases[i], hours[i] * H));
        }
    }

    public static RealmInfo of(Kind kind, long totalSeconds) {
        List<Stage> ladder = switch (kind) {
            case CULTIVATION -> CULTIVATION;
            case BODY -> BODY;
            case MASTERY -> MASTERY;
        };
        int idx = 0;
        for (int i = 0; i < ladder.size(); i++) {
            if (totalSeconds >= ladder.get(i).threshold()) {
                idx = i;
            }
        }
        Stage cur = ladder.get(idx);
        Stage next = idx + 1 < ladder.size() ? ladder.get(idx + 1) : null;
        double progress = next == null
                ? 1.0
                : (double) (totalSeconds - cur.threshold()) / (next.threshold() - cur.threshold());
        return new RealmInfo(kind.name(), totalSeconds, idx, cur.major(), cur.name(), cur.threshold(),
                next == null ? null : next.name(),
                next == null ? null : next.threshold(),
                Math.max(0, Math.min(1, progress)));
    }
}
