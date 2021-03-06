/*
 * Copyright 2018 SJTU IST Lab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cn.edu.sjtu.ist.ops.common;

import com.google.gson.Gson;
import java.util.List;

public class ShuffleConf {

    private final TaskConf task;
    private final OpsNode dstNode;
    private final List<Integer> nums;

    public ShuffleConf(TaskConf task, OpsNode dstNode, List<Integer> nums) {
        this.task = task;
        this.dstNode = dstNode;
        this.nums = nums;
    }

    public TaskConf getTask() {
        return this.task;
    }

    public OpsNode getDstNode() {
        return this.dstNode;
    }

    public List<Integer> getNums() {
        return this.nums;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}