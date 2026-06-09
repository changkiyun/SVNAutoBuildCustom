// Groovy
/**
 * 공유 라이브러리 – 빌드·배포 파이프라인
 */
def call(Map cfg = [:]) {
    def isDefferent = false
    def excludePattern = ""
    if (cfg.EXCLUDEDFILES != null) {
        excludePattern = cfg.EXCLUDEDFILES.collect { it }.join("|")
    }
    
    pipeline {
        agent any
        stages {
            stage('clear') {
                steps {
                    script {
                        //초기화시 워크스페이스 데이터를 삭제
                        if (cfg.INIT != null && cfg.INIT == true) {
                            sh """
                            rm -rf *
                            mkdir result
                            """
                        } else {
                            sh """
                            rm -rf result
                            mkdir result
                            """
                        }
                    }
                }
            }
            stage('Checkout Revision') {
                parallel {
                    stage('Checkout Latest And Build') {
                        steps {
                            //head 리비전 체크아웃
                            checkout([$class: 'SubversionSCM', 
                                    locations: [[credentialsId: "${cfg.CREDENTIALSID}",
                                                depthOption: 'infinity',
                                                ignoreExternalsOption: true,
                                                local: ".", 
                                                remote: "${cfg.REMOTE_PATH}"+"${cfg.BRANCH_NAME}"+'@' + "head"]],
                                    workspaceUpdater: [$class: 'UpdateUpdater']])

                            //젠킨스 내부에 저장된 빌드 파일 복사
                            script {
                                sh "cp -f /var/jenkins_home/buildFile/build.xml ${env.WORKSPACE}/build.xml" // 파일 복사
                                sh "cp -f /var/jenkins_home/buildFile/build_${cfg.ZENIUS_VERSION}.properties ${env.WORKSPACE}/build.properties" // 파일 복사
                            }
                            
                            //빌드
                            withAnt(installation: 'ant') {
                                sh "ant -f ${env.WORKSPACE}/build.xml clean build"
                            }

                            //빌드 압축 해제
                            script {
                                sh "mkdir -p result/latest_version"
                                sh "unzip -o ${env.WORKSPACE}/build/${cfg.ZENIUS_VERSION}.zip -d ${env.WORKSPACE}/result/latest_version/${cfg.ZENIUS_VERSION}/"
                                sh "unzip -o ${env.WORKSPACE}/build/${cfg.ZENIUS_VERSION}_oz.zip -d ${env.WORKSPACE}/result/latest_version/${cfg.ZENIUS_VERSION}_oz/"
                            }
                        }
                    }
                    stage('Checkout specific And Build') {
                        steps {
                            script {
                                // 차이점 분석을 위해 SPECIFIC_REVISION 빌드는 항상 수행
                                def revDir = "old/${cfg.SPECIFIC_REVISION}"
                                // 기존에 체크아웃된 리비전이 있는지 확인
                                if (!fileExists(revDir)) {
                                    //용량 최적화를 위해 있던 리비전 제거
                                    sh "rm -rf old/*"
                                    echo "▶ Directory '${revDir}' not found. Creating and checking out..."
                                    sh "mkdir -p '${env.WORKSPACE}/${revDir}'"
                                    // 특정 리비전 체크아웃
                                    checkout([
                                        $class: 'SubversionSCM',
                                        locations: [[
                                        credentialsId: "${cfg.CREDENTIALSID}",
                                        depthOption: 'infinity',
                                        ignoreExternalsOption: true,
                                        local: revDir,
                                        remote: "${cfg.REMOTE_PATH}${cfg.BRANCH_NAME}@${cfg.SPECIFIC_REVISION}"
                                        ]],
                                        workspaceUpdater: [$class: 'UpdateUpdater']
                                    ])
                                    
                                    // 빌드 파일 복사
                                    sh "cp -f /var/jenkins_home/buildFile/build.xml ${env.WORKSPACE}/${revDir}/build.xml" // 파일 복사
                                    sh "cp -f /var/jenkins_home/buildFile/build_${cfg.ZENIUS_VERSION}.properties ${env.WORKSPACE}/${revDir}/build.properties" // 파일 복사
                                    
                                    // 빌드
                                    withAnt(installation: 'ant') {
                                        sh "ant -f ${env.WORKSPACE}/${revDir}/build.xml clean build"
                                    }

                                    //빌드 압축 해제 및 zip 파일 생성
                                    sh "mkdir ${env.WORKSPACE}/${revDir}/build/unzip"
                                    sh "unzip -o ${env.WORKSPACE}/${revDir}/build/${cfg.ZENIUS_VERSION}.zip -d ${env.WORKSPACE}/${revDir}/build/unzip/${cfg.ZENIUS_VERSION}/"
                                    sh "unzip -o ${env.WORKSPACE}/${revDir}/build/${cfg.ZENIUS_VERSION}_oz.zip -d ${env.WORKSPACE}/${revDir}/build/unzip/${cfg.ZENIUS_VERSION}_oz/"
                                    sh """
                                        cd ${env.WORKSPACE}/${revDir}/build/unzip
                                        zip -r ${cfg.SPECIFIC_REVISION}_version.zip ./*
                                        mv ${cfg.SPECIFIC_REVISION}_version.zip ../${cfg.SPECIFIC_REVISION}_version.zip
                                    """
                                } else {
                                    echo "ℹ️ Directory '${revDir}' already exists. Skipping checkout."
                                }
                            }
                        }
                    }
                }
            }
            stage('Compare and Extract Differences') {
                steps {
                    script {
                        // 차이점 추출은 항상 수행 (SVN 커밋 등에 필요)
                        try {
                            sh """
                                cd ./result
                                diff -rq ${env.WORKSPACE}/old/${cfg.SPECIFIC_REVISION}/build/unzip/ latest_version/ > diff_result.txt
                                if [ -s diff_result.txt ]; then
                                    echo "Differences found:"
                                    cat diff_result.txt
                                else
                                    echo "No differences found."
                                fi
                            """
                            script {
                                sh "cp ${env.WORKSPACE}/old/${cfg.SPECIFIC_REVISION}/build/${cfg.SPECIFIC_REVISION}_version.zip ${cfg.DEPLOY_FOLDER}.zip"
                            }
                        } catch (e) {
                            isDefferent = true
                            echo "differences found."
                            sh """
                                cd ./result
                                mkdir -p diff_files

                                # 변경된 파일 추출
                                awk '/differ/ { if (\$4 !~ /${excludePattern}/) print \$4 }' diff_result.txt | xargs -I{} cp --parents {} diff_files/
                                
                                # 추가된 파일 추출
                                awk '/^Only in latest_version/ {sub(":", "", \$3); print \$3 "/" \$NF}' diff_result.txt | xargs -I{} cp -r --parents {} diff_files/
                                
                                # 삭제할 파일 스크립트 생성
                                OLD_PATH="${env.WORKSPACE}/old/${cfg.SPECIFIC_REVISION}/build/unzip/"
                                awk -v path="\$OLD_PATH" 'index(\$0, "Only in " path) == 1 {sub(":", "", \$3);filePath = \$3 "/" \$NF;gsub(path, "", filePath);printf "rm -f \\"%s\\"\\n", filePath;}' diff_result.txt > diff_files/delete_removed_files.sh

                                if [ -s diff_files/delete_removed_files.sh ]; then chmod +x diff_files/delete_removed_files.sh && mv diff_files/delete_removed_files.sh diff_files/latest_version/delete_removed_files.sh; fi
                                
                                cd diff_files/latest_version
                                zip -r ${cfg.DEPLOY_FOLDER}.zip ./*
                            """
                            script {
                                sh "cp -r ${env.WORKSPACE}/old/${cfg.SPECIFIC_REVISION}/build/${cfg.SPECIFIC_REVISION}_version.zip ${cfg.SPECIFIC_REVISION}_version.zip"
                                sh "mv result/diff_files/latest_version/${cfg.DEPLOY_FOLDER}.zip ${cfg.DEPLOY_FOLDER}.zip"
                            }
                        }
                    }
                }
            }
            stage('Execute Remote Command') {
                steps {
                    script {
                        def transferArgs = [
                            sourceFiles: "",
                            remoteDirectory: "${cfg.TEST_WEB_PATH}",
                            removePrefix: "" 
                        ]

                        // [전송 분기] INIT 여부에 따라 원격 서버로 보낼 파일 설정
                        if (cfg.INIT != null && cfg.INIT == true) {
                            // 전체 빌드 본 전송 (build/ 접두사 제거하여 zip 파일만 전송)
                            transferArgs.sourceFiles = "build/${cfg.ZENIUS_VERSION}.zip,build/${cfg.ZENIUS_VERSION}_oz.zip"
                            transferArgs.removePrefix = "build"
                        } else {
                            // 차이점 패치 파일들 전송
                            transferArgs.sourceFiles = isDefferent ? "${cfg.SPECIFIC_REVISION}_version.zip,${cfg.DEPLOY_FOLDER}.zip" : "${cfg.DEPLOY_FOLDER}.zip"
                        }

                        if (cfg.AUTO_RELOAD != null && cfg.AUTO_RELOAD) {
                            def command = "set -x && cd ${cfg.TEST_WEB_PATH} && "
                            
                            // [STEP 1] 백업 처리
                            if (cfg.FILES_TO_BACKUP != null && cfg.FILES_TO_BACKUP.size() > 0) {
                                command += "echo '▶ [STEP 1] Backup files...' && "
                                command += "rm -rf backup && "
                                command += "mkdir -p backup && "

                                cfg.FILES_TO_BACKUP.each { filePath ->
                                    command += "if [ -f \"${filePath}\" ]; then cp --parents \"${filePath}\" backup/; fi && "
                                }
                            }
                            
                            // [STEP 2] 기존 컨텍스트 폴더 제거
                            command += "echo '▶ [STEP 2] Remove old contexts...' && "
                            command += "rm -rf ${cfg.ZENIUS_VERSION} ${cfg.ZENIUS_VERSION}_oz && "
                            
                            // [배포 분기] INIT 여부에 따라 복구 및 압축 해제 명령 구성
                            if (cfg.INIT != null && cfg.INIT == true) {
                                // [INIT TRUE] 전체 압축 파일 해제
                                command += "echo '▶ [STEP 3] Extract full build outputs...' && "
                                command += "unzip -o ${cfg.ZENIUS_VERSION}.zip -d ./ && "
                                command += "unzip -o ${cfg.ZENIUS_VERSION}_oz.zip -d ./ && "
                                
                                // [STEP 4] 백업 파일 복구 (backup/. 사용)
                                command += "echo '▶ [STEP 4] Restore backup files...' && "
                                command += "if [ -d backup ]; then cp -r backup/. ./; fi && "
                                
                                // [STEP 5] 임시 압축파일 및 백업 폴더 정리
                                command += "echo '▶ [STEP 5] Cleanup temporary files...' && "
                                command += "rm -rf backup ${cfg.ZENIUS_VERSION}.zip ${cfg.ZENIUS_VERSION}_oz.zip && "
                            } else {
                                // [INIT FALSE] 패치 배포 로직 (기존 로직 유지)
                                command += "echo '▶ [STEP 3] Restore specific version if exists...' && "
                                command += "if [ -f \"${cfg.SPECIFIC_REVISION}_version.zip\" ]; then unzip -o ${cfg.SPECIFIC_REVISION}_version.zip -d ./; fi && "
                                
                                command += "echo '▶ [STEP 4] Apply deployment package...' && "
                                command += "unzip -o ${cfg.DEPLOY_FOLDER}.zip -d ./ && "
                                
                                command += "echo '▶ [STEP 5] Run file deletion script if exists...' && "
                                command += "if [ -f \"delete_removed_files.sh\" ]; then chmod +x \"delete_removed_files.sh\"; ./delete_removed_files.sh; fi && "
                                
                                // [STEP 6] 백업 파일 복구 (backup/. 사용)
                                command += "echo '▶ [STEP 6] Restore backup files...' && "
                                command += "if [ -d backup ]; then cp -r backup/. ./; fi && "
                                
                                // [STEP 7] 임시 파일 정리
                                command += "echo '▶ [STEP 7] Cleanup temporary files...' && "
                                command += "rm -rf backup ${cfg.SPECIFIC_REVISION}_version.zip && "
                            }
                            
                            // [STEP 8] 톰캣 재시작
                            command += "echo '▶ [STEP 8] Restarting Tomcat...' && "
                            command += "(${cfg.TEST_WEB_PATH}/../bin/shutdown.sh || true) && "
                            command += "${cfg.TEST_WEB_PATH}/../bin/startup.sh && "
                            command += "echo \"[DEPLOY COMPLETED]\""

                            transferArgs.execCommand = command
                            echo "Generated Remote Command: ${command}"
                        }
                        sshPublisher(
                            continueOnError: false,
                            failOnError: true,
                            publishers: [
                                sshPublisherDesc(
                                    configName: "${cfg.TEST_SERVER_IP}",
                                    transfers: [
                                        sshTransfer(transferArgs)
                                    ]
                                )
                            ]
                        )
                    }
                }
            }
            stage('Clean Directory') {
                steps {
                    script {
                        // 특정 리비전 zip 파일 및 임시 result 폴더 삭제
                        sh "rm -rf ${cfg.SPECIFIC_REVISION}_version.zip result"
                    }
                }
            }
            stage('Archive Differences') {
                steps {
                    script {
                        // 아카이브는 항상 수행 (수정 사항 파일 보관용)
                        archiveArtifacts artifacts: "${cfg.DEPLOY_FOLDER}.zip"
                    }
                }
            }
            stage('Commit Deploy Folder') {
                steps {
                    script {
                        echo "Commit changed files to SVN Deploy Folder..."
                    }
                }
            }
        }
    }
}