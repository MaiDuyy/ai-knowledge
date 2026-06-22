package com.security.security.service;

import com.security.security.entity.WikiPage;
import com.security.security.entity.WikiLink;
import com.security.security.entity.enumeration.SecurityClassification;
import com.security.security.entity.enumeration.WikiPageType;
import com.security.security.repository.WikiPageRepository;
import com.security.security.repository.WikiLinkRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Component
public class BenchmarkDataSeeder {

    private final WikiPageRepository wikiPageRepository;
    private final WikiLinkRepository wikiLinkRepository;

    public BenchmarkDataSeeder(WikiPageRepository wikiPageRepository, WikiLinkRepository wikiLinkRepository) {
        this.wikiPageRepository = wikiPageRepository;
        this.wikiLinkRepository = wikiLinkRepository;
    }

    @Transactional
    public void clearAll() {
        wikiLinkRepository.deleteAll();
        wikiPageRepository.deleteAll();
    }

    @Transactional
    public List<WikiPage> seed() {
        clearAll();

        List<WikiPage> pages = new ArrayList<>();

        // 1. page-public-1 (Workspace: ws-default, Dept: ALL, AllowedRoles: ALL, PUBLIC)
        pages.add(WikiPage.builder()
                .title("Chính sách làm việc từ xa")
                .slug("page-public-1")
                .content("Nội dung chính sách làm việc từ xa dành cho toàn bộ nhân viên công ty.")
                .workspaceId("ws-default")
                .departmentId("ALL")
                .allowedRoles("ALL")
                .securityClassification(SecurityClassification.PUBLIC)
                .pageType(WikiPageType.TOPIC)
                .summary("Chính sách làm việc từ xa.")
                .build());

        // 2. page-internal-1 (Workspace: ws-it, Dept: dept-it, AllowedRoles: MEMBER, INTERNAL)
        pages.add(WikiPage.builder()
                .title("Hướng dẫn Onboarding IT")
                .slug("page-internal-1")
                .content("Nội dung hướng dẫn onboard cho lập trình viên mới của phòng ban IT. Đọc thêm tại [[page-confidential-1]].")
                .workspaceId("ws-it")
                .departmentId("dept-it")
                .allowedRoles("MEMBER")
                .securityClassification(SecurityClassification.INTERNAL)
                .pageType(WikiPageType.CONCEPT)
                .summary("Hướng dẫn onboard cho phòng IT.")
                .build());

        // 3. page-confidential-1 (Workspace: ws-it, Dept: dept-it, AllowedRoles: HEAD, CONFIDENTIAL)
        pages.add(WikiPage.builder()
                .title("Cấu hình CI/CD IT")
                .slug("page-confidential-1")
                .content("Tài liệu kỹ thuật chi tiết cấu hình CI/CD IT nội bộ. Xem thêm mật khẩu tại [[page-restricted-1]].")
                .workspaceId("ws-it")
                .departmentId("dept-it")
                .allowedRoles("HEAD")
                .securityClassification(SecurityClassification.CONFIDENTIAL)
                .pageType(WikiPageType.SOURCE)
                .summary("Cấu hình CI/CD phòng IT.")
                .build());

        // 4. page-restricted-1 (Workspace: ws-it, Dept: dept-it, AllowedRoles: HEAD, RESTRICTED)
        pages.add(WikiPage.builder()
                .title("Khóa bảo mật IT Production")
                .slug("page-restricted-1")
                .content("Các khóa bảo mật và thông tin cấu hình môi trường production nhạy cảm của phòng IT.")
                .workspaceId("ws-it")
                .departmentId("dept-it")
                .allowedRoles("HEAD")
                .securityClassification(SecurityClassification.RESTRICTED)
                .pageType(WikiPageType.ENTITY)
                .summary("Thông tin bảo mật production IT.")
                .build());

        // 5. page-hr-public (Workspace: ws-hr, Dept: dept-hr, AllowedRoles: ALL, PUBLIC)
        pages.add(WikiPage.builder()
                .title("Tuyển dụng nhân sự")
                .slug("page-hr-public")
                .content("Chính sách tuyển dụng nhân sự công khai của công ty. Quy trình đánh giá tại [[page-hr-internal]].")
                .workspaceId("ws-hr")
                .departmentId("dept-hr")
                .allowedRoles("ALL")
                .securityClassification(SecurityClassification.PUBLIC)
                .pageType(WikiPageType.TOPIC)
                .summary("Thông tin tuyển dụng nhân sự.")
                .build());

        // 6. page-hr-internal (Workspace: ws-hr, Dept: dept-hr, AllowedRoles: MEMBER, INTERNAL)
        pages.add(WikiPage.builder()
                .title("Quy trình đánh giá hiệu suất")
                .slug("page-hr-internal")
                .content("Quy trình đánh giá hiệu suất của nhân viên nội bộ HR. Chi tiết bảng lương tại [[page-hr-confidential]].")
                .workspaceId("ws-hr")
                .departmentId("dept-hr")
                .allowedRoles("MEMBER")
                .securityClassification(SecurityClassification.INTERNAL)
                .pageType(WikiPageType.CONCEPT)
                .summary("Quy trình đánh giá hiệu suất HR.")
                .build());

        // 7. page-hr-confidential (Workspace: ws-hr, Dept: dept-hr, AllowedRoles: HEAD, CONFIDENTIAL)
        pages.add(WikiPage.builder()
                .title("Kế hoạch lương thưởng HR")
                .slug("page-hr-confidential")
                .content("Kế hoạch phân bổ lương thưởng và chế độ đãi ngộ chi tiết của phòng HR.")
                .workspaceId("ws-hr")
                .departmentId("dept-hr")
                .allowedRoles("HEAD")
                .securityClassification(SecurityClassification.CONFIDENTIAL)
                .pageType(WikiPageType.SOURCE)
                .summary("Kế hoạch lương thưởng HR.")
                .build());

        // 8. page-global-public (Workspace: ALL, Dept: ALL, AllowedRoles: ALL, PUBLIC)
        pages.add(WikiPage.builder()
                .title("Bộ quy tắc ứng xử")
                .slug("page-global-public")
                .content("Bộ quy tắc ứng xử nội bộ áp dụng cho toàn bộ tổ chức ở tất cả các phòng ban.")
                .workspaceId("ALL")
                .departmentId("ALL")
                .allowedRoles("ALL")
                .securityClassification(SecurityClassification.PUBLIC)
                .pageType(WikiPageType.TOPIC)
                .summary("Quy tắc ứng xử toàn công ty.")
                .build());

        // 9. page-global-head (Workspace: ALL, Dept: ALL, AllowedRoles: HEAD, CONFIDENTIAL)
        pages.add(WikiPage.builder()
                .title("Kế hoạch nhân sự năm")
                .slug("page-global-head")
                .content("Kế hoạch và dự toán nguồn lực nhân sự cấp cao cho toàn công ty.")
                .workspaceId("ALL")
                .departmentId("ALL")
                .allowedRoles("HEAD")
                .securityClassification(SecurityClassification.CONFIDENTIAL)
                .pageType(WikiPageType.CONCEPT)
                .summary("Kế hoạch nhân sự toàn công ty.")
                .build());

        // 10. page-guest-public (Workspace: ws-default, Dept: ALL, AllowedRoles: ALL, PUBLIC)
        pages.add(WikiPage.builder()
                .title("Tài liệu cộng tác viên")
                .slug("page-guest-public")
                .content("Tài liệu hướng dẫn cộng tác viên bên ngoài tham gia đóng góp công việc.")
                .workspaceId("ws-default")
                .departmentId("ALL")
                .allowedRoles("ALL")
                .securityClassification(SecurityClassification.PUBLIC)
                .pageType(WikiPageType.TOPIC)
                .summary("Hướng dẫn CTV.")
                .build());

        // Save all pages
        List<WikiPage> savedPages = wikiPageRepository.saveAll(pages);

        // Find saved page IDs to create WikiLinks
        Long publicId = getPageId(savedPages, "page-public-1");
        Long internalId = getPageId(savedPages, "page-internal-1");
        Long confidentialId = getPageId(savedPages, "page-confidential-1");
        Long hrPublicId = getPageId(savedPages, "page-hr-public");
        Long hrInternalId = getPageId(savedPages, "page-hr-internal");

        List<WikiLink> links = new ArrayList<>();
        if (publicId != null) {
            links.add(WikiLink.builder().fromPageId(publicId).toSlug("page-internal-1").build());
        }
        if (internalId != null) {
            links.add(WikiLink.builder().fromPageId(internalId).toSlug("page-confidential-1").build());
        }
        if (confidentialId != null) {
            links.add(WikiLink.builder().fromPageId(confidentialId).toSlug("page-restricted-1").build());
        }
        if (hrPublicId != null) {
            links.add(WikiLink.builder().fromPageId(hrPublicId).toSlug("page-hr-internal").build());
        }
        if (hrInternalId != null) {
            links.add(WikiLink.builder().fromPageId(hrInternalId).toSlug("page-hr-confidential").build());
        }

        wikiLinkRepository.saveAll(links);

        return savedPages;
    }

    private Long getPageId(List<WikiPage> pages, String slug) {
        return pages.stream()
                .filter(p -> p.getSlug().equals(slug))
                .map(WikiPage::getId)
                .findFirst()
                .orElse(null);
    }
}
