import { request } from '../utils/request'
import { refreshCsrf } from './auth'
export const registrationApi = {
  options: () => request.get('/auth/registration-options', { quiet: true }),
  async submit(body, bootstrap) {
    await refreshCsrf()
    return request.post(bootstrap ? '/auth/register-admin' : '/auth/register', body, { quiet: true })
  },
  list: params => request.get('/registrations', { params, quiet: true }),
  review: (id, body) => request.post(`/registrations/${encodeURIComponent(id)}/review`, body, { quiet: true }),
}
