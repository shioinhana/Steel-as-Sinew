package org.fsrpg.client;

import java.util.List;

/** Real catalogue cycling, invalid-input rejection and combat interruption of cosmetic playback. */
public final class InspectionScheduleTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        var choices=List.of("one","two","three");var state=new IdleInspection(2047);
        for(int i=0;i<9;i++){
            double now=i*100;
            check(state.request(now,true,choices,id->2),"H starts a grounded ready inspection");
            check(state.action().equals(choices.get(i%3)),"Manual cycles every authored variant deterministically");
            state.update(now,true,false,10,30,choices,id->2);
            check(state.action().equals(choices.get(i%3)),"Auto disabled does not cancel a manual animation");
            check(state.started()==now,"Multiple samples keep the same action origin");
            for(int j=1;j<40;j++)state.update(now+j,true,false,10,30,choices,id->2);
            check(!state.action().isEmpty(),"A manual action remains active to its real endpoint");
            state.update(now+40,true,false,10,30,choices,id->2);
            check(state.action().isEmpty()&&Double.isNaN(state.deadline()),"No automatic restart when auto disabled");
        }
        check(!state.request(900,false,choices,id->2),"Movement or air rejects a manual request");
        check(!state.request(900,true,choices,"unknown",id->2),"Unlisted actions cannot start");
        check(!state.request(Double.NaN,true,choices,id->2),"Nonfinite origin cannot start");
        check(!state.request(900,true,choices,id->Double.NaN),"Nonfinite duration cannot start");
        check(state.request(900,true,choices,"two",id->2),"Explicit authored action starts");
        state.interrupt(901);state.update(901,true,true,10,30,choices,id->2);
        check(state.action().isEmpty()&&!state.request(902,true,choices,id->2),"Combat input interrupts immediately and cannot reopen in the same input");
        check(state.request(904,true,choices,id->2),"Fresh manual action after interruption lease");
        state.update(905,false,true,10,30,choices,id->2);check(state.action().isEmpty(),"Movement cancels instead of pausing");
        var auto=new IdleInspection(42);double now=0;String previous="";
        for(int n=0;n<30;n++){
            auto.update(now,true,true,1,1,choices,id->.2);double deadline=auto.deadline();
            for(double tick=now+1;tick<=deadline;tick++)auto.update(tick,true,true,1,1,choices,id->.2);
            check(!auto.action().isEmpty()&&!auto.action().equals(previous),"Automatic selection avoids consecutive repeats across three variants");
            previous=auto.action();now=deadline+4;auto.update(now,true,true,1,1,choices,id->.2);
        }
        System.out.println("InspectionScheduleTest passed: "+checks+" checks");
    }
}
