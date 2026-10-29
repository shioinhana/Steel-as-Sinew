package org.fsrpg.dev;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

/** Development launcher only: select the secondary screen before the loading window exists. */
public final class MonitorAgent {
    public static void premain(String ignored, Instrumentation instrumentation) {
        // This workstation's second physical screen is Windows adapter DISPLAY5.
        String display = System.getProperty("fsrpg.testDisplay", "\\\\.\\DISPLAY5");
        boolean scriptedReview = Boolean.getBoolean("fsrpg.runAirSmoke") || Boolean.getBoolean("fsrpg.inspectionCarrySmoke") || Boolean.getBoolean("fsrpg.groundImpulseSmoke") || Boolean.getBoolean("fsrpg.burstSwipeSmoke") || Boolean.getBoolean("fsrpg.shoulderCombatSmoke") || Boolean.getBoolean("fsrpg.spearGestureSmoke") || Boolean.getBoolean("fsrpg.combatRevisionSmoke") || Boolean.getBoolean("fsrpg.dualSourceSmoke") || Boolean.getBoolean("fsrpg.guardComboSmoke") || Boolean.getBoolean("fsrpg.creatureCombatSmoke") || Boolean.getBoolean("fsrpg.combatBalanceSmoke") || Boolean.getBoolean("fsrpg.spearPerformanceSmoke")
                || Boolean.getBoolean("fsrpg.cameraScheduleSmoke") || Boolean.getBoolean("fsrpg.shoulderInteractionSmoke") || Boolean.getBoolean("fsrpg.importedItemHandsSmoke") || Boolean.getBoolean("fsrpg.importedItemsSmoke") || Boolean.getBoolean("fsrpg.whiteAdventurerSmoke") || Boolean.getBoolean("fsrpg.rapierLockSmoke")
                || Boolean.getBoolean("fsrpg.weaponSmoke") || Boolean.getBoolean("fsrpg.actionLimitsSmoke") || Boolean.getBoolean("fsrpg.avatarSmoke");
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
                                              ProtectionDomain domain, byte[] bytes) {
                if (scriptedReview && "net/minecraft/client/KeyboardHandler".equals(name)) {
                    ClassReader input = new ClassReader(bytes);
                    ClassWriter output = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                    input.accept(new ClassVisitor(Opcodes.ASM9, output) {
                        @Override public MethodVisitor visitMethod(int access, String method, String descriptor,
                                                                   String signature, String[] exceptions) {
                            MethodVisitor delegate = super.visitMethod(access, method, descriptor, signature, exceptions);
                            if (!(method.equals("keyPress") || method.equals("charTyped")) || !descriptor.endsWith(")V")) return delegate;
                            // Scripted key mappings still run normally; native Escape/debug keys
                            // must not pause a copied-world test while the user works elsewhere.
                            return new MethodVisitor(Opcodes.ASM9) {
                                @Override public void visitEnd() {
                                    int locals = (access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
                                    for (Type argument : Type.getArgumentTypes(descriptor)) locals += argument.getSize();
                                    delegate.visitCode(); delegate.visitInsn(Opcodes.RETURN);
                                    delegate.visitMaxs(0, locals); delegate.visitEnd();
                                }
                            };
                        }
                    }, 0);
                    System.out.println("FSRPG scripted review: physical keyboard input isolated.");
                    return output.toByteArray();
                }
                if (scriptedReview && "net/minecraft/client/MouseHandler".equals(name)) {
                    ClassReader input = new ClassReader(bytes);
                    ClassWriter output = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                    input.accept(new ClassVisitor(Opcodes.ASM9, output) {
                        @Override public MethodVisitor visitMethod(int access, String method, String descriptor,
                                                                   String signature, String[] exceptions) {
                            MethodVisitor delegate = super.visitMethod(access, method, descriptor, signature, exceptions);
                            if (!method.equals("turnPlayer") || !descriptor.equals("(D)V")) return delegate;
                            // The smoke drives CombatCamera directly; physical mouse input belongs to the user.
                            return new MethodVisitor(Opcodes.ASM9) {
                                @Override public void visitEnd() {
                                    delegate.visitCode(); delegate.visitInsn(Opcodes.RETURN);
                                    delegate.visitMaxs(0, 3); delegate.visitEnd();
                                }
                            };
                        }
                    }, 0);
                    System.out.println("FSRPG weapon smoke: physical mouse rotation isolated.");
                    return output.toByteArray();
                }
                if (!"org/lwjgl/glfw/GLFW".equals(name)) return null;
                ClassReader reader = new ClassReader(bytes);
                ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                    @Override protected String getCommonSuperClass(String a, String b) { return "java/lang/Object"; }
                };
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override public MethodVisitor visitMethod(int access, String method, String descriptor,
                                                               String signature, String[] exceptions) {
                        MethodVisitor output = super.visitMethod(access, method, descriptor, signature, exceptions);
                        if (method.equals("glfwGetPrimaryMonitor") && descriptor.equals("()J"))
                            return new AdviceAdapter(Opcodes.ASM9, output, access, method, descriptor) {
                                @Override protected void onMethodEnter() {
                                    int monitors = newLocal(Type.getObjectType("org/lwjgl/PointerBuffer"));
                                    int index = newLocal(Type.INT_TYPE), monitor = newLocal(Type.LONG_TYPE);
                                    visitMethodInsn(INVOKESTATIC, "org/lwjgl/glfw/GLFW", "glfwGetMonitors", "()Lorg/lwjgl/PointerBuffer;", false);
                                    storeLocal(monitors); push(0); storeLocal(index);
                                    Label loop = new Label(), next = new Label(), missing = new Label();
                                    loadLocal(monitors); ifNull(missing); mark(loop);
                                    loadLocal(index); loadLocal(monitors);
                                    visitMethodInsn(INVOKEVIRTUAL, "org/lwjgl/PointerBuffer", "limit", "()I", false);
                                    ifICmp(GE, missing);
                                    loadLocal(monitors); loadLocal(index);
                                    visitMethodInsn(INVOKEVIRTUAL, "org/lwjgl/PointerBuffer", "get", "(I)J", false);
                                    storeLocal(monitor); push(display); loadLocal(monitor);
                                    visitMethodInsn(INVOKESTATIC, "org/lwjgl/glfw/GLFWNativeWin32", "glfwGetWin32Adapter", "(J)Ljava/lang/String;", false);
                                    visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "equalsIgnoreCase", "(Ljava/lang/String;)Z", false);
                                    ifZCmp(EQ, next); loadLocal(monitor); returnValue();
                                    mark(next); iinc(index, 1); goTo(loop); mark(missing);
                                    throwException(Type.getType(IllegalStateException.class), "FSRPG test screen " + display + " is unavailable; no test window was opened.");
                                }
                            };
                        if (method.equals("glfwCreateWindow") && descriptor.equals("(IILjava/lang/CharSequence;JJ)J"))
                            return new AdviceAdapter(Opcodes.ASM9, output, access, method, descriptor) {
                                @Override protected void onMethodEnter() {
                                    // Keep the user's other application focused while tests render on screen 2.
                                    for (int hint : new int[]{0x20001, 0x2000C}) {
                                        push(hint); push(0);
                                        visitMethodInsn(INVOKESTATIC, "org/lwjgl/glfw/GLFW", "glfwWindowHint", "(II)V", false);
                                    }
                                }
                            };
                        return output;
                    }
                }, ClassReader.EXPAND_FRAMES);
                System.out.println("FSRPG development window: " + display + "; automatic focus disabled.");
                return writer.toByteArray();
            }
        });
    }
}
