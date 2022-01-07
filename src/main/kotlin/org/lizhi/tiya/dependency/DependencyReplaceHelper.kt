/*
 * Copyright (C) 2021 Tiya.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.lizhi.tiya.dependency

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.lizhi.tiya.log.FastBuilderLogger
import org.lizhi.tiya.plugin.IPluginContext
import org.lizhi.tiya.task.AARBuilderTask

/**
 * 依赖替换帮助类
 */
class DependencyReplaceHelper(private val pluginContext: IPluginContext) {

    /**
     * 从根工程开始向下替换依赖
     */
    fun replaceDependency() {
        replaceDependency(pluginContext.getApplyProject())
    }

    private val configList = mutableSetOf<String>("api", "runtimeOnly", "implementation")
    private val apiConfigList = mutableSetOf<String>("api", "runtimeOnly", "implementation")

    /**
     * 递归替换依赖
     */
    private fun replaceDependency(currentProject: Project, parent: Project? = null) {
        // 获取所有的模块工程集合
        val moduleProjectList = pluginContext.getModuleProjectList()

        // 从集合中查找到需要替换依赖的module工程, 如果currentProject==app工程, 这里查询结果是null
        val moduleProject = moduleProjectList.firstOrNull { it.moduleExtension.name == currentProject.path }

        // 替换所有待处理的module工程依赖
        for (configuration in currentProject.configurations) {
            // 遍历每一种依赖项集合,例如api、implementation等等
            val mutableSet = mutableSetOf<Dependency>()
            mutableSet.addAll(configuration.dependencies) // 这里转成可变集合来操作
            for (dependency in mutableSet) {
                // 动态删除源码依赖和添加aar依赖
                handleReplaceDependency(configuration, dependency, currentProject)
            }
        }
        // 把下层的依赖投递到上层, 由于下层的module变成aar后会丢失它所引入的依赖,因此需要将这些依赖回传给上层
        if (parent != null && moduleProject != null && moduleProject.cacheValid) {
            // 原始类型
            copyDependencyWithPrefix(currentProject, parent, "")
            // Debug 前缀类型
            copyDependencyWithPrefix(currentProject, parent, "debug")
            // release前缀类型
            copyDependencyWithPrefix(currentProject, parent, "release")
            // 变体前缀
            val flavorName = moduleProject.moduleExtension.flavorName
            if (flavorName.isNotBlank()) {
                //api debugApi tiyaDebugApi
                copyDependencyWithPrefix(currentProject, parent, flavorName)
                copyDependencyWithPrefix(currentProject, parent, flavorName + "Debug")
                copyDependencyWithPrefix(currentProject, parent, flavorName + "Release")
            }
        }
    }


    /**
     * 该方法用来动态替换源码依赖为aar依赖, 如果aar依赖无效,那么会声明aar的任务构建, 此方法会在replaceDependency方法内递归调用多次
     */
    private fun handleReplaceDependency(configuration: Configuration, dependency: Dependency, currentProject: Project) {
        // 获取项目配置
        val projectExtension = pluginContext.getProjectExtension()

        // 获取module工程集合
        val moduleProjectList = pluginContext.getModuleProjectList()

        if (dependency !is ProjectDependency) {
            // 如果依赖项不是工程依赖,那么不处理
            return
        }
        // 获取依赖的project工程
        val dependencyProject = dependency.dependencyProject

        // 防止自己引用自己
        if (dependencyProject === currentProject) {
            return
        }

        // 根据依赖工程的名字获取对应的包装类ModuleProject
        val dependencyModuleProject =
            moduleProjectList.firstOrNull { it.moduleExtension.name == dependencyProject.path }

        // 如果当前模块工程在配置项中注册过且生效的才需要处理
        if (dependencyModuleProject != null && dependencyModuleProject.moduleExtension.enable) {
            //标记这个对象被引用了
            dependencyModuleProject.flagHasOut = true

            FastBuilderLogger.logLifecycle("Handle dependency：${currentProject.name}:${dependency.name}  ")
            if (dependencyModuleProject.cacheValid) {
                // 缓存命中
                FastBuilderLogger.logLifecycle("${currentProject.name} 依赖 ${dependencyModuleProject.obtainName()} 缓存命中 ${configuration.state}")
                // 添加依赖路径 todo FastBuilderPlugin已经设置过,这里可不设置?
                /*currentProject.repositories.flatDir { flatDirectoryArtifactRepository ->
                    flatDirectoryArtifactRepository.dir(projectExtension.storeLibsDir)
                }*/
                //https://issuetracker.google.com/issues/165821826
                // 移除原始的project依赖
                configuration.dependencies.remove(dependency)
                // 添加aar依赖
                configuration.dependencies.add(dependencyModuleProject.obtainAARDependency())
            } else {
                FastBuilderLogger.logLifecycle("${currentProject.name} 依赖 ${dependencyModuleProject.obtainName()} 没有命中缓存")
                // aar缓存无效,重新声明要构建的aar
                AARBuilderTask.prepare(pluginContext, dependencyModuleProject)
            }
        }

        // 获取当前工程的包装类ModuleProject
        val currentModuleProject = moduleProjectList.firstOrNull { it.obtainProject() == currentProject }

        // 记录父子工程依赖关系, 后续可能会用上
        if (dependencyModuleProject != null && currentModuleProject != null) {
            currentModuleProject.dependencyModuleProjectList.add(dependencyModuleProject)
        }

        // 处理完当前Project的依赖后,还需要继续处理它依赖的Project的 project依赖, 这是一个递归操作, 由父向子层层递归处理
        replaceDependency(dependencyProject, currentProject)
    }

    /**
     * 将currentProject的依赖copy到parentProject
     */
    private fun copyDependencyWithPrefix(
        currentProject: Project,
        parentProject: Project,
        prefix: String,
        list: Set<String> = apiConfigList
    ) {
        for (configName in list) {
            val newConfigName = if (prefix.isBlank()) {
                configName
            } else {
                prefix + configName.capitalize()
            }
            // ModuleArchiveLogger.logLifecycle("赋值依赖: ${newConfigName}")
            copyDependency(currentProject, parentProject, newConfigName)
        }
    }


    private fun copyDependency(currentProject: Project, parentProject: Project, configName: String) {
        val srcConfig = currentProject.configurations.getByName(configName)
        val dstConfig = parentProject.configurations.getByName(configName)
        val parentContains = parentProject.configurations.names.contains(configName)
        if (parentContains) {
            // 必须要保证依赖配置的名字在父工程存在,比如子工程用了一个叫xxxApi的configName,父工程也需要存在
            copyDependency(srcConfig, dstConfig)
        }
    }

    private fun copyDependency(src: Configuration, dst: Configuration) {
        for (dependency in src.dependencies) {
            if (dependency is ModuleDependency) {
                val srcExclude = configMatchExclude(src, dependency)
                val destExclude = configMatchExclude(dst, dependency)
                // 如果当前依赖存在忽略配置中,那么不需要拷贝
                if (srcExclude || destExclude) {
                    continue
                } else {
                    /**
                     * 如果子工程或者父工程配置了依赖忽略配置,那么需要给每一项依赖增加一条依赖忽略关系,例如这种配置:
                     * configurations {
                     *  all*.exclude group:'org.jetbrains.kotlin',module:'kotlin-stdlib-jre7'
                     *  all*.exclude group:'com.yibasan.lizhifm.sdk.network',module:'http'
                     *  all*.exclude group:'com.lizhi.component.lib',module:'itnet-http-lib'
                     * }
                     */
                    src.excludeRules.forEach {
                        dependency.exclude(mapOf("group" to it.group, "module" to it.module))
                    }
                    dst.excludeRules.forEach {
                        dependency.exclude(mapOf("group" to it.group, "module" to it.module))
                    }
                    // dependency.excludeRules.addAll(src.excludeRules)
                    // dependency.excludeRules.addAll(dest.excludeRules)
                }
            }
            // 将子工程的依赖添加到父工程中
            dst.dependencies.add(dependency)
        }
    }

    /**
     * 判断当前依赖是否已经加入到忽略规则里面
     */
    private fun configMatchExclude(configuration: Configuration, dependency: Dependency): Boolean {
        for (excludeRule in configuration.excludeRules) {
            return if (excludeRule.module.isNullOrBlank()) {
                dependency.group == excludeRule.group
            } else {
                dependency.group == excludeRule.group && dependency.name == excludeRule.module
            }
        }
        return false
    }
}