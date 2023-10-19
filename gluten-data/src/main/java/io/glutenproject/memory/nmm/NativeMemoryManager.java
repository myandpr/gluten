/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.glutenproject.memory.nmm;

import io.glutenproject.GlutenConfig;
import io.glutenproject.backendsapi.BackendsApiManager;
import io.glutenproject.memory.alloc.NativeMemoryAllocators;

import org.apache.spark.util.TaskResource;
import org.apache.spark.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NativeMemoryManager implements TaskResource {

  private static final Logger LOGGER = LoggerFactory.getLogger(NativeMemoryManager.class);

  private final long nativeInstanceHandle;
  private final String name;
  private final ReservationListener listener;

  private NativeMemoryManager(
      String name, long nativeInstanceHandle, ReservationListener listener) {
    this.name = name;
    this.nativeInstanceHandle = nativeInstanceHandle;
    this.listener = listener;
  }

  public static NativeMemoryManager create(String name, ReservationListener listener) {
    long allocatorId = NativeMemoryAllocators.getDefault().globalInstance().getNativeInstanceId();
    long reservationBlockSize = GlutenConfig.getConf().memoryReservationBlockSize();
    return new NativeMemoryManager(
        name,
        create(
            BackendsApiManager.getBackendName(), name, allocatorId, reservationBlockSize, listener),
        listener);
  }

  public long getNativeInstanceHandle() {
    return this.nativeInstanceHandle;
  }

  public byte[] collectMemoryUsage() {
    return collectMemoryUsage(nativeInstanceHandle);
  }

  public long shrink(long size) {
    return shrink(nativeInstanceHandle, size);
  }

  private static native long shrink(long memoryManagerId, long size);

  private static native long create(
      String backendType,
      String name,
      long allocatorId,
      long reservationBlockSize,
      ReservationListener listener);

  private static native void release(long memoryManagerId);

  private static native byte[] collectMemoryUsage(long memoryManagerId);

  @Override
  public void release() throws Exception {
    release(nativeInstanceHandle);
    if (listener.getUsedBytes() != 0) {
      LOGGER.warn(
          name
              + " Reservation listener still reserved non-zero bytes, which may cause "
              + "memory leak, size: "
              + Utils.bytesToString(listener.getUsedBytes()));
    }
  }

  @Override
  public int priority() {
    return 0; // lowest release priority
  }

  @Override
  public String resourceName() {
    return name + "_mem";
  }
}