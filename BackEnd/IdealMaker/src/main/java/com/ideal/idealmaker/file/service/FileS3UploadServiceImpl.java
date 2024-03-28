package com.ideal.idealmaker.file.service;


import static com.ideal.idealmaker.exception.ExceptionMessage.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import com.ideal.idealmaker.exception.IllegalExtensionException;
import com.ideal.idealmaker.file.dto.FileInfoDto;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetUrlRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.InputStream;
import java.net.URL;
import java.net.HttpURLConnection;


@Service
@RequiredArgsConstructor
public class FileS3UploadServiceImpl implements FileS3UploadService {
    private final S3Client s3Client;

    @Value("${cloud.aws.s3.bucket}")
    private String bucketName;

    /**
     * 파일이름 랜덤생성 메소드
     *
     * @param extension
     * @return
     */
    private String generateRandomName(String extension) {
        String random = UUID.randomUUID().toString();
        return random + "." + extension;
    }

    /**
     * 확장자 제한하는 메소드
     *
     * @param extension
     * @return
     */
    private String getContentType(String extension) {
        switch (extension.toLowerCase()) {
            case "jpeg":
            case "jpg":
            case "png":
                return "image/" + extension;
            default:
                throw new IllegalExtensionException(EXTENSION_ILLEGAL);
        }
    }

    /**
     * S3저장소에 파일 업로드
     *
     * @param uploadPath : 저장 폴더 명
     *                   : 게시글은 userId/postId/ 이런식으로 만들어서 관리
     * @param multipartFile
     * @return : 이미지가 저장된 주소 (URL)
     */
    @Override
    public FileInfoDto uploadFile(String uploadPath, MultipartFile multipartFile) {
        String originName = multipartFile.getOriginalFilename(); //원본 이미지 이름
        String ext = StringUtils.getFilenameExtension(originName); //확장자

        String generatedName = uploadPath + generateRandomName(ext); //새로 생성된 이미지 이름

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(generatedName)
                .contentType(getContentType(ext))
                .build();

        try {
            s3Client.putObject(putObjectRequest, RequestBody.fromBytes(multipartFile.getBytes()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        String fileURL = s3Client.utilities().getUrl(GetUrlRequest.builder().bucket(bucketName).key(generatedName).build()).toExternalForm();
        return FileInfoDto.builder()
                .fileName(generatedName)
                .fileURL(fileURL)
                .build();
    }

    /**
     * 파일을 여러개 저장하는 메소드
     *
     * @param uploadPath
     * @param multipartFiles
     * @return
     */
    @Override
    public List<FileInfoDto> uploadFlieList(String uploadPath, List<MultipartFile> multipartFiles) {
        List<FileInfoDto> fileInfos = new ArrayList<>();
        for (MultipartFile file : multipartFiles) {
            fileInfos.add(uploadFile(uploadPath, file));
        }
        return fileInfos;
    }

    /**
     * S3저장소에서 파일 삭제
     *
     * @param fileName : db에 저장할 imageName에 해당
     */
    @Override
    public void removeFile(String fileName) {
        DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(fileName)
                .build();
        s3Client.deleteObject(deleteRequest);
    }

    /**
     * 업로드 된 폴더를 전체 삭제하고 싶을 때
     * s3 버킷에서 uploadPath안의 객체 전체 삭제
     *
     * @param uploadPath
     */
    @Override
    public void removeFolderFiles(String uploadPath) {
        // 버킷에 들어있는 객체 조회
        ListObjectsV2Response listObjectsResponse = getObjectsFromS3Bucket(uploadPath);

        // Object Key 값을 params에 넣어 delete 실행
        for (S3Object s3Object : listObjectsResponse.contents()) {
            String key = s3Object.key();
            removeFile(key);
        }

        // 남은 객체 체크
        listObjectsResponse = getObjectsFromS3Bucket(uploadPath);

        // 남은 객체가 없다면 종료, 있다면 함수 재실행
        if (listObjectsResponse.contents().isEmpty()) {
            System.out.println(uploadPath + " 폴더 삭제를 종료합니다");
        } else {
            System.out.println(uploadPath + " 폴더 삭제를 재실행합니다");
            removeFolderFiles(uploadPath);
        }
    }

    // 버킷에 들어있는 Object 조회하는 함수
    private ListObjectsV2Response getObjectsFromS3Bucket(String uploadPath) {
        ListObjectsV2Request listObjectsRequest = ListObjectsV2Request.builder()
                .bucket(bucketName)
                .prefix(uploadPath)
                .build();

        return s3Client.listObjectsV2(listObjectsRequest);
    }

    /**
     * 이미지 url을 s3 버킷에 업로드
     * 
     * @param idealId
     * @param imageUrl
     * @return
     */
    @Override
    public FileInfoDto uploadImageURL(String idealId, String imageUrl) {

        String ext = StringUtils.getFilenameExtension(imageUrl); //확장자

        String generatedName = idealId + "/" + generateRandomName(ext); //새로 생성된 이미지 이름


        try {
            // 외부 이미지 URL에서 InputStream 생성
            URL url = new URL(imageUrl);
            HttpURLConnection connection = (HttpURLConnection)url.openConnection();
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            InputStream inputStream = connection.getInputStream();

            // S3에 이미지 업로드
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(generatedName)
                .contentType(getContentType(ext))
                .build();

            s3Client.putObject(putObjectRequest,
                RequestBody.fromInputStream(inputStream, connection.getContentLengthLong()));

            // 리소스 해제
            inputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        String fileURL = s3Client.utilities().getUrl(GetUrlRequest.builder().bucket(bucketName).key(generatedName).build()).toExternalForm();
        return FileInfoDto.builder()
            .fileName(generatedName)
            .fileURL(fileURL)
            .build();
    }
}
