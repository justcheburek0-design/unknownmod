package com.unknownmod;
import net.minecraft.server.command.ServerCommandSource;
import java.lang.reflect.Method;
public class Dump {
    public static void main(String[] args) {
        for(Method m : ServerCommandSource.class.getMethods()) {
            if (m.getName().toLowerCase().contains("perm")) {
                System.out.println(m.getName());
            }
        }
    }
}
