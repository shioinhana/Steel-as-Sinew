package org.fsrpg.quest;

import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.fsrpg.quest.QuestDefinition.Rank;

/** Global server policy; dates use the configured timezone and never the client's clock. */
public record QuestSettings(int maxTracked,int dailyCount,String timezone,List<Promotion> promotions) {
    public record Promotion(Rank rank,int totalCompletions,Map<String,Integer> requiredQuests,String examQuest) {
        public Promotion { requiredQuests = requiredQuests == null ? Map.of() : Map.copyOf(requiredQuests); }
    }
    public QuestSettings {
        timezone = timezone == null ? "Asia/Shanghai" : timezone;
        promotions = promotions == null ? List.of() : List.copyOf(promotions);
        if (maxTracked < 1 || maxTracked > 16 || dailyCount < 0 || dailyCount > 64) throw new IllegalArgumentException("invalid quest settings limits");
        ZoneId.of(timezone);
        var ranks = new HashSet<Rank>();
        for (Promotion p : promotions) {
            if (p == null || p.rank()==null || p.rank()==Rank.D || !ranks.add(p.rank()) || p.totalCompletions()<0 || p.totalCompletions()>1_000_000
                    || p.examQuest()==null || p.examQuest().isBlank()) throw new IllegalArgumentException("invalid promotion definition");
            p.requiredQuests().forEach((id,count) -> { if(id==null || count==null || count<1 || count>1_000_000)throw new IllegalArgumentException("invalid promotion requirement"); });
        }
    }
    public static QuestSettings defaults() { return new QuestSettings(3,3,"Asia/Shanghai",List.of()); }
    public void validate(QuestCatalog catalog) {
        for (Promotion p : promotions) {
            QuestDefinition exam=catalog.get(p.examQuest());
            if (exam.rules().examRank()!=p.rank()) throw new IllegalArgumentException("promotion exam rank mismatch: "+p.examQuest());
            p.requiredQuests().keySet().forEach(catalog::get);
        }
    }
}
