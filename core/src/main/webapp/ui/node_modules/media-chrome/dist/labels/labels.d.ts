export type MediaErrorLike = {
    code: number;
    message: string;
    [key: string]: any;
};
export declare const formatError: (error: MediaErrorLike) => {
    title: any;
    message: any;
};
