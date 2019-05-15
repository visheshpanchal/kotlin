/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.targets.js.npm

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinSingleTargetExtension
import org.jetbrains.kotlin.gradle.dsl.kotlinExtensionOrNull
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinJsCompilation
import org.jetbrains.kotlin.gradle.targets.js.RequiredKotlinJsDependency
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension
import org.jetbrains.kotlin.gradle.targets.js.nodejs.nodeJs

/**
 * Visits given gradle [project] for all of its [NpmProject],
 * creates `package.json` for them ([NpmProjectPackage]) and runs
 * selected [NodeJsRootExtension.packageManager] to download and install all of it's dependencies.
 */
internal class NpmProjectVisitor(val resolver: NpmResolver, val project: Project) {
    private val npmProjects = mutableListOf<NpmProjectPackage>()
    private val byCompilation = mutableMapOf<KotlinJsCompilation, NpmProjectPackage>()
    private val byNpmDependency = mutableMapOf<NpmDependency, NpmProjectPackage>()
    private val taskRequirements = mutableMapOf<RequiresNpmDependencies, Collection<RequiredKotlinJsDependency>>()
    private val requiredFromTasksByCompilation = mutableMapOf<KotlinJsCompilation, MutableList<Dependency>>()

    private fun addTaskRequirements(task: RequiresNpmDependencies) {
        val requirements = task.requiredNpmDependencies.toList()

        taskRequirements[task] = requirements

        val requiredDependenciesList = requiredFromTasksByCompilation.getOrPut(task.compilation) { mutableListOf() }
        requirements.forEach { requiredDependency ->
            requiredDependenciesList.add(requiredDependency.createDependency(project))
        }
    }

    private fun addNpmProject(resolved: NpmProjectPackage) {
        val compilation = resolved.npmProject.compilation
        byCompilation[compilation] = resolved
        resolved.npmDependencies.forEach {
            byNpmDependency[it] = resolved
        }
        npmProjects.add(resolved)
    }

    fun visitProject(): NpmProjects {
        visitTasksRequiredDependencies()

        val kotlin = project.kotlinExtensionOrNull

        if (kotlin != null) {
            when (kotlin) {
                is KotlinSingleTargetExtension -> visitTarget(kotlin.target)
                is KotlinMultiplatformExtension -> kotlin.targets.forEach {
                    visitTarget(it)
                }
            }
        }

        return NpmProjects(
            project,
            npmProjects,
            byCompilation,
            byNpmDependency,
            taskRequirements
        )
    }

    private fun visitTasksRequiredDependencies() {
        project.tasks.toList().forEach { task ->
            if (task.enabled && task is RequiresNpmDependencies) {
                addTaskRequirements(task)
            }
        }
    }

    private fun visitTarget(target: KotlinTarget) {
        if (target.platformType == KotlinPlatformType.js) {
            target.compilations.toList().forEach { compilation ->
                compilation as KotlinJsCompilation
                visitCompilation(compilation)
            }
        }
    }

    private fun visitCompilation(compilation: KotlinJsCompilation) {
        val project = compilation.target.project
        val npmProject = compilation.npmProject
        val name = npmProject.name
        val packageJson = PackageJson(
            name,
            project.version.toString()
        )
        val npmDependencies = mutableSetOf<NpmDependency>()
        val gradleDeps = NpmProjectGradleDeps()

        compilation.allKotlinSourceSets.forEach { sourceSet ->
            sourceSet.relatedConfigurationNames.forEach { configurationName ->
                val configuration = project.configurations.getByName(configurationName)
                visitConfiguration(configuration, npmDependencies, gradleDeps)
            }
        }

        packageJson.main = npmProject.main

        val requiredByTasks = requiredFromTasksByCompilation[compilation]
        if (requiredByTasks != null && requiredByTasks.isNotEmpty()) {
            val configuration = project.configurations.create("$name-jsTools")
            requiredByTasks.forEach {
                configuration.dependencies.add(it)
            }
            configuration.resolve()
            visitConfiguration(configuration, npmDependencies, gradleDeps)
        }

        npmDependencies.forEach {
            packageJson.dependencies[it.key] = resolver.chooseVersion(packageJson.dependencies[it.key], it.version)
        }

        gradleDeps.externalModules.forEach {
            val relativePath = it.path.relativeTo(npmProject.dir)
            packageJson.dependencies[it.name] = "file:$relativePath"
        }

        gradleDeps.internalModules.forEach { target ->
            val resolvedTarget = resolver.findDependentResolvedNpmProject(project, target)
            if (resolvedTarget != null) {
                packageJson.dependencies[resolvedTarget.packageJson.name] = resolvedTarget.packageJson.version
            }
        }

        project.nodeJs.packageJsonHandlers.forEach {
            it(packageJson)
        }

        val npmPackage = NpmProjectPackage(project, npmProject, npmDependencies, packageJson)
        npmPackage.packageJson.saveTo(npmProject.packageJsonFile)

        resolver.packageManager.resolveProject(npmPackage)

        addNpmProject(npmPackage)
    }

    private fun visitConfiguration(
        configuration: Configuration,
        npmDependencies: MutableSet<NpmDependency>,
        gradleDeps: NpmProjectGradleDeps
    ) {
        resolver.gradleNodeModules.collectDependenciesFromConfiguration(configuration, gradleDeps)

        configuration.allDependencies.forEach { dependency ->
            when (dependency) {
                is NpmDependency -> npmDependencies.add(dependency)
            }
        }
    }
}