export class HttpError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

export const badRequest = (message) => new HttpError(400, 'bad_request', message);
export const unauthorized = (message = 'Token inválido o ausente') => new HttpError(401, 'unauthorized', message);
export const forbidden = (message = 'No tienes acceso a este recurso') => new HttpError(403, 'forbidden', message);
export const notFound = (message = 'No encontrado') => new HttpError(404, 'not_found', message);
export const conflict = (message) => new HttpError(409, 'conflict', message);
