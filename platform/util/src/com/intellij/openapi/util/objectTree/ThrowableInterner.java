/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.openapi.util.objectTree;

import com.intellij.openapi.util.Comparing;
import com.intellij.util.concurrency.AtomicFieldUpdater;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.FilteringIterator;
import com.intellij.util.containers.Interner;
import gnu.trove.TObjectHashingStrategy;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.util.Arrays;

/**
 * Please don't look, there's nothing interesting here.
 *
 *
 *
 *
 * If you insist, JVM stores stacktrace information in compact form in Throwable.backtrace field, but blocks reflective access to this field.
 * This class uses this field for comparing Throwables.
 * The available method Throwable.getStackTrace() unfortunately can't be used for that because it's
 * 1) too slow and 2) explodes Throwable retained size by polluting Throwable.stackTrace fields.
 */
class ThrowableInterner {
  private static final Interner<Throwable> myTraceInterner = new Interner<Throwable>(new TObjectHashingStrategy<Throwable>() {
    @Override
    public int computeHashCode(Throwable throwable) {
      String message = throwable.getMessage();
      if (message != null) {
        return message.hashCode();
      }
      Object[] backtrace = getBacktrace(throwable);
      if (backtrace != null) {
        // 5 is there interesting stack trace elements start
        Object[] stack = (Object[])ContainerUtil.find(backtrace, FilteringIterator.instanceOf(Object[].class));
        return ((Class)stack[5]).getName().hashCode();
      }
      return throwable.getStackTrace()[5].getClassName().hashCode();
    }

    @Override
    public boolean equals(Throwable o1, Throwable o2) {
      if (o1 == o2) return true;
      if (o1 == null || o2 == null) return false;

      if (!Comparing.equal(o1.getClass(), o2.getClass())) return false;
      if (!Comparing.equal(o1.getMessage(), o2.getMessage())) return false;
      if (!equals(o1.getCause(), o2.getCause())) return false;
      Object backtrace1 = getBacktrace(o1);
      Object backtrace2 = getBacktrace(o2);
      if (backtrace1 != null && backtrace2 != null) {
        return compareArrays(backtrace1, backtrace2);
      }
      return Arrays.equals(o1.getStackTrace(), o2.getStackTrace());
    }
  });

  private static final long BACKTRACE_FIELD_OFFSET;
  static {
    Field firstField = Throwable.class.getDeclaredFields()[1];
    long firstFieldOffset = AtomicFieldUpdater.getUnsafe().objectFieldOffset(firstField);
    BACKTRACE_FIELD_OFFSET = firstFieldOffset == 12 ? 8 : firstFieldOffset == 16 ? 12 : firstFieldOffset == 24 ? 16 : -1;
    if (BACKTRACE_FIELD_OFFSET == -1
        || !firstField.getName().equals("detailMessage")
        || !(AtomicFieldUpdater.getUnsafe().getObject(new Throwable(), BACKTRACE_FIELD_OFFSET) instanceof Object[])) {
      throw new RuntimeException("Unknown layout: "+firstField+";"+firstFieldOffset+". Please specify -Didea.disposer.debug=off in idea.properties to suppress");
    }
  }

  private static Object[] getBacktrace(@NotNull Throwable throwable) {
    // the JVM blocks access to Throwable.backtrace via reflection
    Object backtrace = AtomicFieldUpdater.getUnsafe().getObject(throwable, BACKTRACE_FIELD_OFFSET);
    // obsolete jdk
    return backtrace instanceof Object[] && ((Object[])backtrace).length == 5 ? (Object[])backtrace : null;
  }

  private static boolean compareArrays(Object a1, Object a2) {
    if (a1 == a2) return true;
    if (a1 == null || a2 == null) return false;
    if (a1.equals(a2)) return true;
    if (a1 instanceof int[]) {
      return a2 instanceof int[] && Arrays.equals((int[])a1, (int[])a2);
    }
    if (a1 instanceof short[]) {
      return a2 instanceof short[] && Arrays.equals((short[])a1, (short[])a2);
    }
    if (a1 instanceof Object[]) {
      if (!(a2 instanceof Object[])) {
        return false;
      }
      Object[] oa1 = (Object[])a1;
      Object[] oa2 = (Object[])a2;

      if (oa1.length != oa2.length) {
        return false;
      }
      for (int i = 0; i < oa1.length; i++) {
        Object o1 = oa1[i];
        Object o2 = oa2[i];
        if (!compareArrays(o1, o2)) return false;
      }
      return true;
    }
    return false;
  }

  @NotNull
  static Throwable intern(@NotNull Throwable throwable) {
    return getBacktrace(throwable) == null ? throwable : myTraceInterner.intern(throwable);
  }
}
