#ifndef YANDEX_PDF_LIB_H
#define YANDEX_PDF_LIB_H

#ifdef __cplusplus
extern "C" {
#endif

char* yandex_sign_track_url(const char* track_id, const char* version, const char* timestamp, const char* auth_token);
char* yandex_sign_batch_url(const char* track_ids, const char* version, const char* timestamp, const char* auth_token);
void yandex_free_string(char* ptr);

#ifdef __cplusplus
}
#endif

#endif /* YANDEX_PDF_LIB_H */
