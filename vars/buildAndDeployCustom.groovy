// Groovy
/**
 * 공유 라이브러리 – 빌드·배포 파이프라인
 *
 * 호출 예:
 *   buildAndDeploy(
 *       CREDENTIALSID      : 'svn-cred-id',
 *       REMOTE_PATH        : 'svn://example.com/repo/',
 *       BRANCH_NAME        : 'branches/myBranch/',
 *       SPECIFIC_REVISION  : '1234',
 *       ZENIUS_VERSION     : '1.2.3',
 *       DEPLOY_FOLDER      : 'deploy_pkg',
 *       SCP_USER           : 'deployUser',
 *       SCP_PW             : 'deployPw',
 *       TEST_SERVER_IP     : '192.0.2.10',
 *       TEST_WEB_PATH      : '/home/deploy/web',
 *       FILES_TO_BACKUP    : ["zenius8/WEB-INF/conf/db.properties", "zenius8/WEB-INF/conf/zenius.properties"],
 *       EXCLUDEDFILES      : ["config.xml", "README.md"]
*        INIT               : true,
 *   )
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
                        // 두 디렉토리의 차이점 비교 ....
                        try {
                            // 차이가 없을 경우 
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
                            // 차이가 있을 경우
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
                            remoteDirectory: "${cfg.TEST_WEB_PATH}"
                        ]

                        transferArgs.sourceFiles = isDefferent ? "${cfg.SPECIFIC_REVISION}_version.zip,${cfg.DEPLOY_FOLDER}.zip" : "${cfg.DEPLOY_FOLDER}.zip"

                        if (cfg.AUTO_RELOAD != null && cfg.AUTO_RELOAD) {
                            //FILES_TO_BACKUP    : ["zenius8/WEB-INF/conf/db.properties", "zenius8/WEB-INF/conf/zenius.properties"]
                            def command = "cd ${cfg.TEST_WEB_PATH} && "
                            if (cfg.FILES_TO_BACKUP != null && cfg.FILES_TO_BACKUP.size() > 0) {
                                command += "rm -rf backup && "
                                command += "mkdir -p backup && "

                                cfg.FILES_TO_BACKUP.each { filePath ->
                                    command += "if [ -f \"${filePath}\" ]; then cp --parents \"${filePath}\" backup/; fi && "
                                }
                            }
                            // 기존 컨텍스트 폴더 제거
                            command += "rm -rf ${cfg.ZENIUS_VERSION} ${cfg.ZENIUS_VERSION}_oz && "
                            // 이전 리비전으로 복구
                            command += "if [ -f \"${cfg.SPECIFIC_REVISION}_version.zip\" ]; then unzip -o ${cfg.SPECIFIC_REVISION}_version.zip -d ./; fi && "
                            // 변경된 파일 적용
                            command += "unzip -o ${cfg.DEPLOY_FOLDER}.zip -d ./ && "
                            // 삭제된 파일 적용
                            command += "if [ -f \"delete_removed_files.sh\" ]; then chmod +x \"delete_removed_files.sh\"; ./delete_removed_files.sh; fi && "
                            // 백업 파일 복구
                            command += "cp -r backup/* ./ && "
                            // 폴더 정리
                            command += "rm -rf backup ${cfg.SPECIFIC_REVISION}_version.zip && "
                            // 톰캣 재시작
                            command += "${cfg.TEST_WEB_PATH}/../bin/shutdown.sh && "
                            command += "${cfg.TEST_WEB_PATH}/../bin/startup.sh && "
                            command += "echo \"[DEPLOY COMPLETED]\""

                            transferArgs.execCommand = command
                            echo "${command}"
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
                        sh """
                            rm -rf ${cfg.SPECIFIC_REVISION}_version.zip result
                        """
                    }
                }
            }
            stage('Archive Differences') {
                steps {
                    // 생성된 ${cfg.DEPLOY_FOLDER}.zip 파일을 아티팩트로 아카이브
                    archiveArtifacts artifacts: "${cfg.DEPLOY_FOLDER}.zip"
                }
            }
        }
    }
}